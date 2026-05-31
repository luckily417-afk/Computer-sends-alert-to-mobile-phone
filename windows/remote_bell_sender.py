import json
import os
import queue
import threading
import tkinter as tk
from tkinter import messagebox
from urllib.parse import quote
from urllib.request import Request, urlopen


DEFAULT_SERVER = "https://ntfy.sh"
DEFAULT_TOPIC = "rb-liuc-2f4c9d7a8e6b4130a5d2c8e9f1b7a6c3"
APP_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "RemoteBellSender")
CONFIG_PATH = os.path.join(APP_DIR, "config.json")


def load_config():
    try:
        with open(CONFIG_PATH, "r", encoding="utf-8") as file:
            data = json.load(file)
    except Exception:
        data = {}
    return {
        "server": data.get("server", DEFAULT_SERVER),
        "topic": data.get("topic", DEFAULT_TOPIC),
        "message": data.get("message", "请查看手机"),
    }


def save_config(server, topic, message):
    os.makedirs(APP_DIR, exist_ok=True)
    with open(CONFIG_PATH, "w", encoding="utf-8") as file:
        json.dump(
            {"server": server, "topic": topic, "message": message},
            file,
            ensure_ascii=False,
            indent=2,
        )


def send_message(server, topic, message):
    clean_server = server.strip().rstrip("/")
    clean_topic = topic.strip()
    if not clean_server or not clean_topic:
        raise ValueError("服务器和频道不能为空")
    if not message.strip():
        message = "收到远程提醒"

    url = f"{clean_server}/{quote(clean_topic, safe='')}"
    data = message.encode("utf-8")
    request = Request(
        url,
        data=data,
        method="POST",
        headers={
            "Content-Type": "text/plain; charset=utf-8",
            "Title": "Remote Bell",
            "Priority": "urgent",
            "Tags": "bell",
        },
    )
    with urlopen(request, timeout=20) as response:
        response.read()
        if response.status >= 400:
            raise RuntimeError(f"发送失败：HTTP {response.status}")


class RemoteBellApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Remote Bell Sender")
        self.geometry("520x430")
        self.minsize(480, 390)
        self.configure(bg="#f7f8fa")
        self.result_queue = queue.Queue()

        config = load_config()
        self.server_var = tk.StringVar(value=config["server"])
        self.topic_var = tk.StringVar(value=config["topic"])
        self.status_var = tk.StringVar(value="准备发送")

        self.build_ui(config["message"])
        self.after(150, self.poll_result_queue)

    def build_ui(self, message):
        container = tk.Frame(self, bg="#f7f8fa", padx=22, pady=20)
        container.pack(fill="both", expand=True)

        title = tk.Label(
            container,
            text="Remote Bell Sender",
            bg="#f7f8fa",
            fg="#181b1f",
            font=("Microsoft YaHei UI", 18, "bold"),
        )
        title.pack(anchor="w")

        self.add_labeled_entry(container, "中转服务器", self.server_var)
        self.add_labeled_entry(container, "频道", self.topic_var)

        message_label = tk.Label(
            container,
            text="附带文字",
            bg="#f7f8fa",
            fg="#181b1f",
            font=("Microsoft YaHei UI", 10),
        )
        message_label.pack(anchor="w", pady=(16, 6))

        self.message_text = tk.Text(
            container,
            height=7,
            wrap="word",
            font=("Microsoft YaHei UI", 11),
            relief="solid",
            bd=1,
        )
        self.message_text.insert("1.0", message)
        self.message_text.pack(fill="both", expand=True)

        send_button = tk.Button(
            container,
            text="发送响铃",
            command=self.start_send,
            height=2,
            bg="#1769e0",
            fg="white",
            activebackground="#0f58c5",
            activeforeground="white",
            font=("Microsoft YaHei UI", 12, "bold"),
        )
        send_button.pack(fill="x", pady=(18, 8))
        self.send_button = send_button

        status = tk.Label(
            container,
            textvariable=self.status_var,
            bg="#f7f8fa",
            fg="#3f4652",
            anchor="w",
            font=("Microsoft YaHei UI", 10),
        )
        status.pack(fill="x")

    def add_labeled_entry(self, parent, text, variable):
        label = tk.Label(
            parent,
            text=text,
            bg="#f7f8fa",
            fg="#181b1f",
            font=("Microsoft YaHei UI", 10),
        )
        label.pack(anchor="w", pady=(16, 6))

        entry = tk.Entry(
            parent,
            textvariable=variable,
            font=("Microsoft YaHei UI", 11),
            relief="solid",
            bd=1,
        )
        entry.pack(fill="x", ipady=8)

    def start_send(self):
        server = self.server_var.get().strip().rstrip("/")
        topic = self.topic_var.get().strip()
        message = self.message_text.get("1.0", "end").strip()
        try:
            save_config(server, topic, message)
        except Exception as exc:
            messagebox.showerror("保存失败", str(exc))
            return

        self.send_button.config(state="disabled")
        self.status_var.set("正在发送...")
        thread = threading.Thread(
            target=self.send_worker,
            args=(server, topic, message),
            daemon=True,
        )
        thread.start()

    def send_worker(self, server, topic, message):
        try:
            send_message(server, topic, message)
        except Exception as exc:
            self.result_queue.put(("error", str(exc)))
        else:
            self.result_queue.put(("ok", "已发送"))

    def poll_result_queue(self):
        try:
            status, text = self.result_queue.get_nowait()
        except queue.Empty:
            self.after(150, self.poll_result_queue)
            return

        self.send_button.config(state="normal")
        self.status_var.set(text)
        if status == "error":
            messagebox.showerror("发送失败", text)
        self.after(150, self.poll_result_queue)


if __name__ == "__main__":
    RemoteBellApp().mainloop()
