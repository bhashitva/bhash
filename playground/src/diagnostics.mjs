// SPDX-License-Identifier: Apache-2.0
/** Convert compiler code-point coordinates to CodeMirror UTF-16 offsets. Only source
 * diagnostics for the playground document are accepted; toolchain messages stay text. */
export function compilerDiagnostics(message, source) {
  const lines = source.split('\n');
  const starts = []; let offset = 0;
  for (const line of lines) { starts.push(offset); offset += line.length + 1; }
  const result = [];
  for (const text of String(message).split(/\r?\n/u)) {
    const match = /(?:^|\s)playground\.bhash:(\d+):(\d+):\s*(.+)$/u.exec(text);
    if (!match) continue;
    const line = Number(match[1]), column = Number(match[2]);
    if (line < 1 || line > lines.length || column < 1) continue;
    const content = lines[line - 1].replace(/\r$/u, '');
    const scalars = Array.from(content);
    if (column > scalars.length + 1) continue;
    const from = starts[line - 1] + scalars.slice(0, column - 1).join('').length;
    const to = from + (scalars[column - 1]?.length ?? 0);
    result.push({from, to, line, column, severity: 'error', message: match[3], source: 'Bhash'});
  }
  return result;
}
