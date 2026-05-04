"""Thin paramiko wrapper. The repo layer (product_repo.py) calls into this."""
from __future__ import annotations
import io
import logging
import stat as stat_mod
import threading
from contextlib import contextmanager
from typing import Iterable, Optional

import paramiko


logger = logging.getLogger("SftpClient")


class SftpClient:
    def __init__(
        self,
        host: str,
        port: int,
        username: str,
        private_key_pem: str,
        passphrase: Optional[str],
        connect_timeout: int = 15,
    ):
        self.host = host
        self.port = port
        self.username = username
        self._private_key_pem = private_key_pem
        self._passphrase = passphrase
        self._timeout = connect_timeout
        self._ssh: Optional[paramiko.SSHClient] = None
        self._sftp: Optional[paramiko.SFTPClient] = None
        # paramiko's SFTPClient channel is not thread-safe. Every public method below
        # acquires this lock so the worker thread (image streaming) and UI thread
        # (detail fetch) can't tangle the same channel.
        self._lock = threading.RLock()

    # ------- lifecycle -------

    def connect(self) -> None:
        if self._sftp is not None:
            return
        key = paramiko.RSAKey.from_private_key(
            io.StringIO(self._private_key_pem),
            password=self._passphrase,
        )
        ssh = paramiko.SSHClient()
        ssh.set_missing_host_key_policy(paramiko.AutoAddPolicy())
        ssh.connect(
            hostname=self.host,
            port=self.port,
            username=self.username,
            pkey=key,
            look_for_keys=False,
            allow_agent=False,
            timeout=self._timeout,
        )
        self._ssh = ssh
        self._sftp = ssh.open_sftp()
        logger.info(f"SFTP connected to {self.username}@{self.host}:{self.port}")

    def close(self) -> None:
        try:
            if self._sftp is not None:
                self._sftp.close()
        finally:
            self._sftp = None
        try:
            if self._ssh is not None:
                self._ssh.close()
        finally:
            self._ssh = None

    def __enter__(self) -> "SftpClient":
        self.connect()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()

    @property
    def sftp(self) -> paramiko.SFTPClient:
        if self._sftp is None:
            raise RuntimeError("SFTP not connected. Call connect() first.")
        return self._sftp

    # ------- primitive ops -------

    def exists(self, path: str) -> bool:
        with self._lock:
            try:
                self.sftp.stat(path)
                return True
            except IOError:
                return False

    def is_dir(self, path: str) -> bool:
        with self._lock:
            try:
                return stat_mod.S_ISDIR(self.sftp.stat(path).st_mode)
            except IOError:
                return False

    def stat_size(self, path: str) -> Optional[int]:
        """Return file size in bytes, or None if path doesn't exist."""
        with self._lock:
            try:
                return self.sftp.stat(path).st_size
            except IOError:
                return None

    def listdir(self, path: str) -> Iterable[paramiko.SFTPAttributes]:
        with self._lock:
            return self.sftp.listdir_attr(path)

    def mkdir_p(self, path: str) -> None:
        with self._lock:
            if not self.exists(path):
                self.sftp.mkdir(path)

    def mkdirs(self, path: str) -> None:
        parts = path.strip("/").split("/")
        cur = ""
        for p in parts:
            cur = cur + "/" + p
            self.mkdir_p(cur)

    def read_text(self, path: str, encoding: str = "utf-8") -> str:
        with self._lock:
            with self.sftp.open(path, "rb") as f:
                return f.read().decode(encoding)

    def write_text(self, path: str, text: str, encoding: str = "utf-8") -> None:
        with self._lock:
            with self.sftp.open(path, "wb") as f:
                f.write(text.encode(encoding))

    def read_bytes(self, path: str) -> bytes:
        with self._lock:
            with self.sftp.open(path, "rb") as f:
                return f.read()

    def upload(self, local_path: str, remote_path: str) -> None:
        with self._lock:
            self.sftp.put(local_path, remote_path)

    def download(self, remote_path: str, local_path: str) -> None:
        with self._lock:
            self.sftp.get(remote_path, local_path)

    def remove(self, path: str) -> None:
        with self._lock:
            self.sftp.remove(path)

    def rename(self, old_path: str, new_path: str) -> None:
        with self._lock:
            self.sftp.rename(old_path, new_path)

    def rmdir(self, path: str) -> None:
        with self._lock:
            self.sftp.rmdir(path)

    def delete_tree(self, path: str) -> None:
        """Recursively delete a directory + everything below it. No-op if missing."""
        if not self.exists(path):
            return
        if not self.is_dir(path):
            self.remove(path)
            return
        # Snapshot listing under the lock, then iterate (each child op re-acquires).
        for entry in self.listdir(path):
            sub = f"{path}/{entry.filename}"
            if stat_mod.S_ISDIR(entry.st_mode):
                self.delete_tree(sub)
            else:
                self.remove(sub)
        self.rmdir(path)


@contextmanager
def open_session(host, port, username, private_key_pem, passphrase):
    """`with open_session(...) as cli: ...` — convenience wrapper."""
    cli = SftpClient(host, port, username, private_key_pem, passphrase)
    try:
        cli.connect()
        yield cli
    finally:
        cli.close()
