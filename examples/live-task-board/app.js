// SPDX-License-Identifier: Apache-2.0
"use strict";

const byId = id => document.getElementById(id);
const login = byId("login");
const add = byId("add-task");
const status = byId("connection");
const error = byId("error");
let csrf = "";
let active;

function showError(message) {
    error.textContent = message;
    error.hidden = !message;
}

async function request(path, method = "GET", payload) {
    const headers = {};
    if (method !== "GET") headers["X-CSRF-Token"] = csrf;
    if (payload !== undefined) headers["Content-Type"] = "application/json";
    const response = await fetch(path, {
        method, headers, body: payload === undefined ? undefined : JSON.stringify(payload)
    });
    if (!response.ok) {
        const failure = new Error("अनुरोध पूरा नहीं हुआ (" + response.status + ")।");
        failure.status = response.status;
        throw failure;
    }
    return response.status === 204 ? undefined : response.json();
}

function setConnection(state, message) {
    status.dataset.state = state;
    status.textContent = message;
}

function stopBoard() {
    const previous = active;
    active = undefined;
    if (previous) {
        clearTimeout(previous.retry);
        previous.socket?.close();
    }
    byId("tasks").replaceChildren();
    byId("board-panel").hidden = true;
    byId("login-panel").hidden = false;
}

async function restoreSession() {
    const session = await request("/session");
    csrf = session.csrf;
    if (session.user) startBoard(session);
    else stopBoard();
}

function renderTasks(board, snapshot) {
    const items = snapshot.tasks.map(task => {
        const input = document.createElement("input");
        input.type = "checkbox";
        input.checked = task.done;
        input.addEventListener("change", async () => {
            input.disabled = true;
            try {
                await request(board.api + "/tasks/" + task.id, "PATCH", {done: input.checked});
                await refresh(board);
            } catch (failure) {
                input.checked = task.done;
                await handleError(failure);
            } finally {
                input.disabled = false;
            }
        });
        const label = document.createElement("label");
        label.append(input, document.createTextNode(task.title));
        const li = document.createElement("li");
        li.append(label);
        return li;
    });
    byId("tasks").replaceChildren(...items);
    byId("empty").hidden = items.length !== 0;
}

async function handleError(failure) {
    showError(failure.message);
    if (failure.status === 401) {
        stopBoard();
        try { await restoreSession(); }
        catch (error) { showError(error.message); }
    }
}

async function refresh(board) {
    board.pending = true;
    if (board.refreshing) return board.refreshing;
    board.refreshing = (async () => {
        while (active === board && board.pending) {
            board.pending = false;
            const snapshot = await request(board.api);
            if (active === board && snapshot.revision >= board.revision) {
                board.revision = snapshot.revision;
                renderTasks(board, snapshot);
            }
        }
    })();
    try { await board.refreshing; }
    finally { board.refreshing = undefined; }
}

function connect(board) {
    const address = new URL("/live/" + encodeURIComponent(board.id), location.href);
    address.protocol = location.protocol === "https:" ? "wss:" : "ws:";
    const socket = new WebSocket(address, "bhash.board.v1");
    board.socket = socket;
    setConnection("connecting", "जुड़ रहे हैं…");
    socket.addEventListener("message", async ({data}) => {
        if (active !== board || board.socket !== socket) return;
        try {
            const event = JSON.parse(data);
            if (event.v !== 1) return;
            if (event.type === "ready") {
                await refresh(board);
                if (active === board && socket.readyState === WebSocket.OPEN) {
                    setConnection("live", "लाइव · जुड़े हुए");
                }
            } else if (event.type === "changed" && event.board === board.id
                       && event.revision > board.revision) {
                await refresh(board);
            }
        } catch (failure) {
            if (active === board) await handleError(failure);
        }
    });
    socket.addEventListener("close", async ({code}) => {
        if (active !== board || board.socket !== socket) return;
        if (code === 1008) {
            stopBoard();
            try { await restoreSession(); }
            catch (failure) { showError(failure.message); }
            return;
        }
        setConnection("offline", "संबंध टूटा। फिर जुड़ रहे हैं…");
        board.retry = setTimeout(() => {
            if (active === board) connect(board);
        }, 1000);
    });
}

function startBoard(session) {
    stopBoard();
    const board = {
        id: session.board, api: "/api/boards/" + encodeURIComponent(session.board), revision: -1
    };
    active = board;
    byId("board-name").textContent = session.board === "team" ? "दल का कार्यपट" : "स्टूडियो का कार्यपट";
    byId("login-panel").hidden = true;
    byId("board-panel").hidden = false;
    connect(board);
}

login.addEventListener("submit", async event => {
    event.preventDefault();
    const button = login.querySelector("button");
    button.disabled = true;
    showError("");
    try {
        const {username, password} = Object.fromEntries(new FormData(login));
        const session = await request("/login", "POST", {username, password});
        csrf = session.csrf;
        login.reset();
        startBoard(session);
    } catch (failure) {
        // A rejected login can also mean the anonymous session has expired.
        try { await restoreSession(); }
        catch (error) { showError(error.message); return; }
        showError(failure.message);
    } finally { button.disabled = false; }
});

add.addEventListener("submit", async event => {
    event.preventDefault();
    if (!active) return;
    const board = active;
    const button = add.querySelector("button");
    button.disabled = true;
    showError("");
    try {
        const {title} = Object.fromEntries(new FormData(add));
        await request(board.api + "/tasks", "POST", {title});
        add.reset();
        await refresh(board);
    } catch (failure) { await handleError(failure); }
    finally { button.disabled = false; }
});

byId("logout").addEventListener("click", async () => {
    try {
        await request("/logout", "POST");
        stopBoard();
        showError("");
        await restoreSession();
    } catch (failure) { await handleError(failure); }
});

restoreSession().catch(handleError);
