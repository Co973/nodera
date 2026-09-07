const $ = (s) => document.querySelector(s);
let token,
  state,
  selected,
  last = "",
  polling = false;
function toast(text) {
  $("#toast").textContent = text;
  $("#toast").hidden = false;
  setTimeout(() => ($("#toast").hidden = true), 5500);
}
async function api(path, data) {
  const r = await fetch("/api/" + path, {
    method: data ? "POST" : "GET",
    headers: { "Content-Type": "application/json", "X-Mesh-Token": token },
    body: data ? JSON.stringify(data) : undefined,
  });
  const value = await r.json();
  if (!r.ok) throw Error(value.error || "Request failed");
  return value;
}
const el = (tag, className, text) => {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
};
const size = (n) =>
  n < 1024
    ? `${n} B`
    : n < 1048576
      ? `${(n / 1024).toFixed(0)} KB`
      : `${(n / 1048576).toFixed(1)} MB`;
function peerDialog() {
  $("#peer-dialog").showModal();
  $("#peer-address").focus();
}
$("#add-peer").onclick = peerDialog;
$("#empty-add").onclick = peerDialog;
$("#cancel-peer").onclick = () => $("#peer-dialog").close();
$("#peer-form").onsubmit = async (e) => {
  e.preventDefault();
  const b = e.submitter;
  b.disabled = true;
  try {
    await api("peer", { address: $("#peer-address").value });
    $("#peer-dialog").close();
    await refresh();
    toast("Peer added. They must add your address too.");
  } catch (e) {
    toast(e.message);
  } finally {
    b.disabled = false;
  }
};
$("#unlock-form").onsubmit = async (e) => {
  e.preventDefault();
  const b = e.submitter;
  b.disabled = true;
  try {
    const f = new FormData(e.target);
    await api("unlock", { name: f.get("name"), password: f.get("password") });
    e.target.reset();
    await refresh();
  } catch (e) {
    toast(e.message);
  } finally {
    b.disabled = false;
  }
};
$("#compose").onsubmit = async (e) => {
  e.preventDefault();
  if (!selected || !$("#text").value.trim()) return;
  $("#send").disabled = true;
  try {
    await api("send", { peer: selected, text: $("#text").value });
    $("#text").value = "";
    await refresh();
  } catch (e) {
    toast(e.message);
  } finally {
    $("#send").disabled = !selected;
  }
};
$("#text").onkeydown = (e) => {
  if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    $("#compose").requestSubmit();
  }
};
$("#attach").onclick = () => {
  if (!selected) {
    toast("Choose a peer first");
    return;
  }
  $("#file").click();
};
$("#file").onchange = async (e) => {
  const file = e.target.files[0];
  if (!file) return;
  if (!selected) {
    toast("Choose a peer first");
    e.target.value = "";
    return;
  }
  if (file.size > 25 * 1024 * 1024) {
    toast("This preview supports files up to 25 MB");
    e.target.value = "";
    return;
  }
  try {
    toast("Preparing encrypted file…");
    const data = await new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result.split(",")[1]);
      reader.onerror = reject;
      reader.readAsDataURL(file);
    });
    await api("file", { peer: selected, name: file.name, data });
    await refresh();
    toast("File offered. Your peer chooses when to download.");
  } catch (e) {
    toast(e.message);
  } finally {
    e.target.value = "";
  }
};
$("#fingerprint").onclick = () => {
  const p = state.peers.find((p) => p.id === selected);
  $("#own-fingerprint").textContent = state.identity.id
    .match(/.{1,4}/g)
    .join(" ");
  $("#peer-fingerprint").textContent = p.id.match(/.{1,4}/g).join(" ");
  $("#verify").textContent = p.verified
    ? "Remove verification"
    : "I compared — mark verified";
  $("#identity-dialog").showModal();
};
$("#close-identity").onclick = () => $("#identity-dialog").close();
$("#verify").onclick = async () => {
  try {
    const p = state.peers.find((p) => p.id === selected);
    await api("verify", { peer: selected, verified: !p.verified });
    $("#identity-dialog").close();
    await refresh();
  } catch (e) {
    toast(e.message);
  }
};
async function transfer(f, action) {
  try {
    if (action === "save") {
      const r = await fetch("/api/transfer", {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Mesh-Token": token },
        body: JSON.stringify({ hash: f.hash, peer: f.peer, action }),
      });
      if (!r.ok) throw Error((await r.json()).error);
      const blob = await r.blob();
      // Android WebView does not reliably handle a synthetic anchor download. Its native
      // bridge opens a Storage Access Framework destination and writes the exact bytes there.
      if (window.NoderaAndroid?.saveFile) {
        const dataUrl = await new Promise((resolve, reject) => {
          const reader = new FileReader();
          reader.onload = () => resolve(reader.result);
          reader.onerror = reject;
          reader.readAsDataURL(blob);
        });
        window.NoderaAndroid.saveFile(f.name, dataUrl.split(",", 2)[1]);
        return;
      }
      const url = URL.createObjectURL(blob);
      const a = el("a");
      a.href = url;
      a.download = f.name;
      a.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } else {
      await api("transfer", { hash: f.hash, peer: f.peer, action });
      await refresh();
    }
  } catch (e) {
    toast(e.message);
  }
}
function render() {
  if (state.demo) {
    $(".pill").textContent = "● Demo · 2 local test nodes";
    $("footer span").textContent = "DEMO · TEMPORARY TEST DATA";
  }
  $("#unlock").hidden = true;
  $("#app").hidden = false;
  $("#self-name").textContent = state.identity.name;
  $("#self-avatar").textContent = state.identity.name[0]?.toUpperCase() || "M";
  $("#peer-count").textContent = state.peers.length;
  $("#known").textContent = state.peers.length;
  $("#queued").textContent = state.outbox;
  $("#address").textContent =
    state.addresses.join("\n") || `127.0.0.1:${state.meshPort}`;
  if (!selected && state.peers.length) selected = state.peers[0].id;
  $("#peer-list").replaceChildren(
    ...state.peers.map((p) => {
      const button = el(
          "button",
          "peer" + (p.id === selected ? " selected" : ""),
        ),
        text = el("div");
      button.append(el("span", "avatar", p.name[0]?.toUpperCase() || "?"));
      text.append(
        el("strong", "", p.name),
        el("small", "", p.verified ? "Fingerprint verified" : "Not verified"),
      );
      button.append(text);
      button.onclick = () => {
        selected = p.id;
        last = "";
        render();
      };
      return button;
    }),
  );
  const p = state.peers.find((p) => p.id === selected);
  $("#text").disabled = !p;
  $("#send").disabled = !p;
  $("#fingerprint").hidden = !p;
  if (!p) return;
  $("#chat-name").textContent = p.name;
  $("#chat-avatar").textContent = p.name[0]?.toUpperCase() || "?";
  $("#chat-subtitle").textContent = p.verified
    ? "Fingerprint verified · LAN connection"
    : "First contact · Compare fingerprints to verify";
  const messages = state.messages.filter((m) => m.peer === selected),
    files = state.files.filter((f) => f.peer === selected);
  const key = JSON.stringify([selected, messages, files]);
  if (key === last) return;
  last = key;
  const container = $("#messages"),
    nearBottom =
      container.scrollHeight - container.scrollTop - container.clientHeight <
      70;
  container.replaceChildren();
  if (!messages.length && !files.length) {
    const empty = el("div", "empty");
    empty.append(
      el("div", "empty-icon", "⌁"),
      el("h2", "", `Say hello to ${p.name}.`),
      el("p", "", "Messages wait here until a route becomes available."),
    );
    container.append(empty);
  }
  for (const m of messages) {
    const item = el("div", "message " + m.direction);
    item.append(
      el("div", "bubble", m.text),
      el(
        "small",
        "",
        `${new Date(m.time).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })} · ${m.direction === "out" ? m.status : "received"}`,
      ),
    );
    container.append(item);
  }
  for (const f of files) {
    const item = el("div", "file-card");
    item.append(
      el("strong", "", "↳ " + f.name),
      el(
        "small",
        "",
        `${size(f.size)} · ${f.status} · ${f.direction === "out" ? "shared by you" : "incoming"}`,
      ),
    );
    if (f.direction === "in") {
      const progress = el("progress");
      progress.max = f.chunks;
      progress.value = f.have.length;
      item.append(progress);
      let actions =
        f.status === "complete"
          ? [["save", "Save file"]]
          : f.status === "transferring"
            ? [["pause", "Pause"]]
            : [
                ["accept", f.have.length ? "Resume" : "Accept file"],
                ["decline", "Decline"],
              ];
      if (f.status === "declined") actions = [["accept", "Accept file"]];
      for (const [a, label] of actions) {
        const b = el("button", "", label);
        b.onclick = () => transfer(f, a);
        item.append(b);
      }
      if (f.error) item.append(el("small", "", f.error));
    }
    container.append(item);
  }
  if (nearBottom) container.scrollTop = container.scrollHeight;
}
async function refresh() {
  if (polling) return;
  polling = true;
  try {
    state = await api("state");
    render();
  } finally {
    polling = false;
  }
}
try {
  const session = await api("session");
  token = session.token;
  if (session.exists) {
    $("#unlock-title").textContent = "Welcome back.";
    $("#name-label").hidden = true;
  }
  if (!session.locked) await refresh();
} catch (e) {
  toast(e.message);
}
setInterval(() => {
  if (state) refresh().catch(() => {});
}, 1500);
