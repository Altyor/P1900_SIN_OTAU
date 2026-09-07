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

# SFTP status errors (missing path, permission denied, ...) surface as these
# OSError subclasses — they're real and must propagate untouched, never treated
# as a dead transport worth reconnecting over.
_SFTP_STATUS_ERRORS = (FileNotFoundError, PermissionError, FileExistsError,
                        NotADirectoryError, IsADirectoryError)

# Generic SFTP protocol failures (e.g. AWS Transfer Family's "Cannot rename a
# directory", used deliberately by move_tree to detect unsupported ops) also
# surface as a plain, errno-less OSError — same shape as a genuinely dead
# socket. Match on text instead of type so only real transport failures
# trigger a reconnect, not routine "server said no" responses.
_STALE_SOCKET_PATTERNS = ("socket is closed", "connection reset", "broken pipe",
                           "connection refused", "not connected", "connection aborted")


def _is_stale_socket(exc: BaseException) -> bool:
    """True for exceptions that mean the underlying transport died (idle
    timeout, NAT/firewall drop, server restart) rather than a normal SFTP
    status error. Worth one reconnect+retry before giving up."""
    if isinstance(exc, _SFTP_STATUS_ERRORS):
        return False
    if isinstance(exc, (EOFError, paramiko.SSHException)):
        return True
    if isinstance(exc, OSError):
        msg = str(exc).lower()
        return any(p in msg for p in _STALE_SOCKET_PATTERNS)
    return False


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
        # Idle SSH connections get silently dropped by NAT/firewalls after a
        # few minutes. A keepalive every 30s keeps the path warm and makes a
        # genuinely dead server detectable quickly instead of on the next
        # write, minutes or hours later.
        transport = ssh.get_transport()
        if transport is not None:
            transport.set_keepalive(30)
        self._ssh = ssh
        self._sftp = ssh.open_sftp()
        logger.info(f"SFTP connected to {self.username}@{self.host}:{self.port}")

    def _with_reconnect(self, op):
        """Run `op` (a zero-arg callable using self.sftp); if the transport
        turns out to be dead, reconnect once and retry before giving up."""
        try:
            return op()
        except Exception as e:
            if not _is_stale_socket(e):
                raise
            logger.warning(f"SFTP operation failed ({e!r}); reconnecting and retrying once")
            self.close()
            self.connect()
            return op()

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
        def _op() -> bool:
            try:
                self.sftp.stat(path)
                return True
            except FileNotFoundError:
                return False
        with self._lock:
            return self._with_reconnect(_op)

    def is_dir(self, path: str) -> bool:
        def _op() -> bool:
            try:
                return stat_mod.S_ISDIR(self.sftp.stat(path).st_mode)
            except FileNotFoundError:
                return False
        with self._lock:
            return self._with_reconnect(_op)

    def stat_size(self, path: str) -> Optional[int]:
        """Return file size in bytes, or None if path doesn't exist."""
        def _op() -> Optional[int]:
            try:
                return self.sftp.stat(path).st_size
            except FileNotFoundError:
                return None
        with self._lock:
            return self._with_reconnect(_op)

    def listdir(self, path: str) -> Iterable[paramiko.SFTPAttributes]:
        with self._lock:
            return self._with_reconnect(lambda: self.sftp.listdir_attr(path))

    def mkdir_p(self, path: str) -> None:
        def _op() -> None:
            if not self.exists(path):
                self.sftp.mkdir(path)
        with self._lock:
            self._with_reconnect(_op)

    def mkdirs(self, path: str) -> None:
        parts = path.strip("/").split("/")
        cur = ""
        for p in parts:
            cur = cur + "/" + p
            self.mkdir_p(cur)

    def read_text(self, path: str, encoding: str = "utf-8") -> str:
        def _op() -> str:
            with self.sftp.open(path, "rb") as f:
                return f.read().decode(encoding)
        with self._lock:
            return self._with_reconnect(_op)

    def write_text(self, path: str, text: str, encoding: str = "utf-8") -> None:
        def _op() -> None:
            with self.sftp.open(path, "wb") as f:
                f.write(text.encode(encoding))
        with self._lock:
            self._with_reconnect(_op)

    def read_bytes(self, path: str) -> bytes:
        def _op() -> bytes:
            with self.sftp.open(path, "rb") as f:
                return f.read()
        with self._lock:
            return self._with_reconnect(_op)

    def upload(self, local_path: str, remote_path: str) -> None:
        with self._lock:
            self._with_reconnect(lambda: self.sftp.put(local_path, remote_path))

    def download(self, remote_path: str, local_path: str) -> None:
        with self._lock:
            self._with_reconnect(lambda: self.sftp.get(remote_path, local_path))

    def remove(self, path: str) -> None:
        with self._lock:
            self._with_reconnect(lambda: self.sftp.remove(path))

    def rename(self, old_path: str, new_path: str) -> None:
        with self._lock:
            self._with_reconnect(lambda: self.sftp.rename(old_path, new_path))

    def rmdir(self, path: str) -> None:
        with self._lock:
            self._with_reconnect(lambda: self.sftp.rmdir(path))

    def move_tree(self, src: str, dst: str) -> None:
        """Move src → dst. Tries an atomic directory rename first; if the server
        refuses to rename directories (e.g. S3-backed AWS Transfer Family, which
        answers "Cannot rename a directory"), falls back to recreating the folder
        and renaming each file individually. Both paths stay server-side — no
        file content is downloaded."""
        try:
            self.rename(src, dst)
            return
        except IOError as e:
            logger.info(f"Directory rename {src} → {dst} refused ({e}); moving file-by-file")
        self._move_tree_per_file(src, dst)

    def _move_tree_per_file(self, src: str, dst: str) -> None:
        self.mkdir_p(dst)
        # Snapshot the listing, then move each child (each op re-acquires the lock).
        for entry in self.listdir(src):
            s = f"{src}/{entry.filename}"
            d = f"{dst}/{entry.filename}"
            if stat_mod.S_ISDIR(entry.st_mode):
                self._move_tree_per_file(s, d)
            else:
                self.rename(s, d)
        # Source is now empty — on object stores the prefix may already be gone,
        # so a failed rmdir here is not an error.
        try:
            self.rmdir(src)
        except IOError:
            pass

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
