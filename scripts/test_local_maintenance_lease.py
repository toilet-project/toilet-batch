"""Synthetic Linux interoperability tests; no production paths, network or database."""
import fcntl
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

class LeaseTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(prefix='geupddong-lease-test.',dir='/tmp')
        self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name); self.path=self.root/'.maintenance.lock'
        self.path.touch(mode=0o600)
    def command(self): return ['java','-cp',os.environ['LEASE_TEST_CLASSES'],'LocalMaintenanceLeaseProbe',str(self.path),str(os.geteuid())]
    def probe(self): return subprocess.run(self.command(),capture_output=True,text=True,timeout=10)
    def test_free_lock_and_release(self):
        self.assertEqual(0,self.probe().returncode); self.assertEqual(0,self.probe().returncode)
    def test_python_lockf_excludes_java(self):
        with self.path.open('r+') as f:
            fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
            self.assertEqual(2,self.probe().returncode)
        self.assertEqual(0,self.probe().returncode)
    def test_java_excludes_python_and_process_exit_releases(self):
        self.hold_and_crash('hold')
    def test_same_jvm_reentry_cannot_release_first_lock(self):
        self.hold_and_crash('reentrant')
    def hold_and_crash(self,mode):
        p=subprocess.Popen(self.command()+[mode],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
        try:
            self.assertEqual('ACQUIRED',p.stdout.readline().strip())
            with self.path.open('r+') as f:
                with self.assertRaises(BlockingIOError): fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
            p.kill(); p.wait(timeout=5)
            with self.path.open('r+') as f: fcntl.lockf(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
        finally:
            if p.poll() is None: p.kill(); p.wait(timeout=5)
            p.stdin.close(); p.stdout.close()
    def test_missing_file_rejected_not_created(self):
        self.path.unlink(); self.assertEqual(2,self.probe().returncode); self.assertFalse(self.path.exists())
    def test_public_permissions_rejected(self):
        self.path.chmod(0o644); self.assertEqual(2,self.probe().returncode)
    def test_nonempty_rejected(self):
        self.path.write_bytes(b'x'); self.assertEqual(2,self.probe().returncode)
    def test_hardlink_rejected(self):
        os.link(self.path,self.root/'duplicate'); self.assertEqual(2,self.probe().returncode)
    def test_symlink_rejected(self):
        other=self.root/'other'; self.path.rename(other); self.path.symlink_to(other)
        self.assertEqual(2,self.probe().returncode)

if __name__=='__main__': unittest.main()
