"""Drain subprocess output through a bounded, supervisor-owned pipe/file."""
import os
import subprocess
import threading
import time

LIMIT = 16 * 1024**2


class CappedProcess:
    def __init__(self, command, path, limit=LIMIT):
        if type(limit) is not int or not 0 < limit <= LIMIT:
            raise ValueError('Invalid log limit')
        self.limit = limit
        self.overflow = threading.Event()
        self.failed = threading.Event()
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL
                             | getattr(os, 'O_NOFOLLOW', 0), 0o600)
        self.output = os.fdopen(descriptor, 'wb')
        try:
            self.child = subprocess.Popen(command, stdout=subprocess.PIPE,
                                          stderr=subprocess.STDOUT, close_fds=True)
        except BaseException:
            self.output.close()
            raise
        self.thread = threading.Thread(target=self._drain, daemon=True)
        self.thread.start()

    def _drain(self):
        retained = 0
        try:
            with self.output, self.child.stdout:
                while True:
                    data = self.child.stdout.read1(65536)
                    if not data: break
                    keep = min(len(data), self.limit - retained)
                    self.output.write(data[:keep]); retained += keep
                    if keep != len(data): self.overflow.set()
        except Exception:
            self.failed.set()

    def poll(self):
        if self.overflow.is_set(): raise RuntimeError('Workload log limit exceeded')
        if self.failed.is_set(): raise RuntimeError('Workload log capture failed')
        code = self.child.poll()
        if code is not None:
            self.thread.join(timeout=0.1)
            if self.thread.is_alive(): return None
        if self.overflow.is_set(): raise RuntimeError('Workload log limit exceeded')
        if self.failed.is_set(): raise RuntimeError('Workload log capture failed')
        return code

    def wait(self, timeout):
        deadline = time.monotonic() + timeout
        while True:
            code = self.poll()
            if code is not None: return code
            if time.monotonic() >= deadline: raise subprocess.TimeoutExpired('bounded output', timeout)
            time.sleep(0.05)

    def reap(self, timeout):
        # Used only after the external service/cgroup has been stopped.
        self.child.wait(timeout=timeout)
        self.thread.join(timeout=timeout)
        if self.thread.is_alive(): raise RuntimeError('Output pipe writer survived cleanup')
