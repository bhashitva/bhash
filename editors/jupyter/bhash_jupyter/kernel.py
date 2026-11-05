"""Translate Jupyter requests to the private Bhash notebook worker protocol."""
import atexit
from importlib.metadata import PackageNotFoundError, version
import json
import struct
import subprocess
from tempfile import TemporaryDirectory
from .identifier_profile import identifier_part

from ipykernel.kernelapp import IPKernelApp
from ipykernel.kernelbase import Kernel
from traitlets import Unicode


MAX_FRAME = 1024 * 1024


class Worker:
    def __init__(self, java, compiler):
        self.scratch = TemporaryDirectory(prefix="bhash-worker-")
        try:
            self.process = subprocess.Popen(
                [java, "-Djava.io.tmpdir=" + self.scratch.name, "-cp", compiler,
                 "dev.bhash.backend.jvm.NotebookWorker"],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            )
        except BaseException:
            self.scratch.cleanup()
            raise
        try:
            if self.receive() != {"kind": "hello", "version": 1}:
                raise OSError("Incompatible Bhash notebook worker protocol")
        except BaseException:
            self.close()
            raise

    def send(self, **message):
        data = json.dumps(message, ensure_ascii=False).encode("utf-8")
        if not 0 < len(data) <= MAX_FRAME:
            raise ValueError("Bhash notebook request exceeds the frame limit")
        self.process.stdin.write(struct.pack(">I", len(data)) + data)
        self.process.stdin.flush()

    def receive(self):
        header = self.process.stdout.read(4)
        if len(header) != 4:
            raise EOFError("Bhash worker exited")
        size, = struct.unpack(">I", header)
        if not 0 < size <= MAX_FRAME:
            raise OSError("Invalid Bhash worker frame")
        data = self.process.stdout.read(size)
        if len(data) != size:
            raise EOFError("Incomplete Bhash worker response")
        return json.loads(data.decode("utf-8"))

    def close(self):
        try:
            if self.process.poll() is None:
                self.process.kill()
            self.process.wait(timeout=5)
        finally:
            self.process.stdin.close()
            self.process.stdout.close()
            self.scratch.cleanup()


class BhashKernel(Kernel):
    implementation = "bhash"
    try:
        implementation_version = version("bhash-jupyter")
    except PackageNotFoundError:
        implementation_version = "development"
    language = "bhash"
    language_version = "0.1"
    language_info = {"name": "bhash", "mimetype": "text/plain", "file_extension": ".bhash"}
    banner = "Bhash (भाष) notebook kernel"
    java = Unicode("java", config=True)
    compiler = Unicode(config=True)

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.worker = None
        atexit.register(self.stop_worker)

    def get_worker(self):
        if self.worker is None:
            if not self.compiler:
                raise OSError("Install the Bhash kernelspec with --compiler pointing to the compiler JAR")
            self.worker = Worker(self.java, self.compiler)
        return self.worker

    def stop_worker(self):
        worker, self.worker = self.worker, None
        if worker is not None:
            worker.close()

    @staticmethod
    def identifier_char(char):
        return identifier_part(char)

    @staticmethod
    def identifier_start(code, cursor):
        start = cursor
        while start and BhashKernel.identifier_char(code[start - 1]):
            start -= 1
        return start

    def query(self, kind, code):
        try:
            worker = self.get_worker()
            worker.send(kind=kind, code=code)
            return worker.receive()
        except (KeyboardInterrupt, OSError, EOFError):
            self.stop_worker()
            return {}
        except ValueError:
            return {}

    def do_complete(self, code, cursor_pos):
        start = self.identifier_start(code, cursor_pos)
        reply = self.query("names", code[start:cursor_pos])
        return {"status": "ok", "matches": reply.get("matches", []),
                "cursor_start": start, "cursor_end": cursor_pos, "metadata": {}}

    def do_inspect(self, code, cursor_pos, detail_level=0, omit_sections=(), **kwargs):
        start = self.identifier_start(code, cursor_pos)
        end = cursor_pos
        while end < len(code) and self.identifier_char(code[end]):
            end += 1
        reply = self.query("inspect", code[start:end])
        found = reply.get("found", False)
        return {"status": "ok", "found": found,
                "data": {"text/plain": reply["text"]} if found else {}, "metadata": {}}

    def do_is_complete(self, code):
        try:
            worker = self.get_worker()
            worker.send(kind="complete", code=code)
            reply = worker.receive()
            if reply["kind"] == "complete":
                result = {"status": reply["status"]}
                if result["status"] == "incomplete":
                    result["indent"] = "    "
                return result
        except KeyboardInterrupt:
            self.stop_worker()
        except (OSError, EOFError):
            self.stop_worker()
        except ValueError:
            pass
        return {"status": "unknown"}

    def do_execute(self, code, silent, store_history=True, user_expressions=None,
                   allow_stdin=False, **kwargs):
        previous_stdin = self._allow_stdin
        self._allow_stdin = allow_stdin
        try:
            worker = self.get_worker()
            worker.send(kind="execute", code=code, allowStdin=allow_stdin)
            while True:
                reply = worker.receive()
                kind = reply["kind"]
                if kind == "done":
                    expressions = {
                        key: {"status": "error", "ename": "Unsupported",
                              "evalue": "Use a trailing expression in a Bhash cell.", "traceback": []}
                        for key in (user_expressions or {})
                    }
                    return {"status": "ok", "execution_count": self.execution_count,
                            "payload": [], "user_expressions": expressions}
                if kind == "error":
                    return self.error("BhashError", reply["message"], silent)
                if kind == "input":
                    try:
                        value = self.raw_input(reply["prompt"])
                    except EOFError:
                        value = None
                    try:
                        worker.send(kind="input", value=value)
                    except ValueError:
                        worker.send(kind="input", value=None)
                elif kind == "stream":
                    if not silent:
                        self.send_response(self.iopub_socket, "stream",
                                           {"name": reply["name"], "text": reply["text"]})
                elif kind == "result":
                    if not silent:
                        self.send_response(self.iopub_socket, "execute_result",
                                           {"execution_count": self.execution_count,
                                            "data": {"text/plain": reply["text"]}, "metadata": {}})
                else:
                    raise OSError("Unexpected Bhash worker response")
        except KeyboardInterrupt:
            self.stop_worker()
            return self.error("KeyboardInterrupt", "Bhash execution interrupted. Session cleared.", silent)
        except (OSError, EOFError) as error:
            self.stop_worker()
            return self.error("BhashWorkerError", str(error) + ". Session cleared.", silent)
        except ValueError as error:
            return self.error("BhashWorkerError", str(error), silent)
        finally:
            self._allow_stdin = previous_stdin

    def error(self, name, message, silent):
        content = {"ename": name, "evalue": message, "traceback": message.splitlines()}
        if not silent:
            self.send_response(self.iopub_socket, "error", content)
        return dict(content, status="error", execution_count=self.execution_count)

    def do_shutdown(self, restart):
        self.stop_worker()
        return {"status": "ok", "restart": restart}


if __name__ == "__main__":
    IPKernelApp.launch_instance(kernel_class=BhashKernel)
