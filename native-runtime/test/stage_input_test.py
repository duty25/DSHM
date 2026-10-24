import unittest,tempfile,pathlib,subprocess,sys,os
SCRIPT=pathlib.Path(__file__).resolve().parents[1]/'tools/stage-android-test.py'
class StageInput(unittest.TestCase):
 def fixture(self):
  t=tempfile.TemporaryDirectory(prefix='dshm-stage-contract-');self.addCleanup(t.cleanup);r=pathlib.Path(t.name);p=r/'prefix';s=r/'sdk';(p/'bin').mkdir(parents=True);(p/'lib').mkdir();s.mkdir();(p/'bin/node').write_bytes(b'owned-test-node');(p/'lib/libtest.so.1').write_bytes(b'owned-test-library');(s/'package.json').write_text('{}');return r,p,s
 def test_regular_files_and_internal_symlink_are_materialized(self):
  r,p,s=self.fixture();(p/'lib/libtest.so').symlink_to('libtest.so.1');out=r/'out';subprocess.run([sys.executable,str(SCRIPT),str(p),str(s),str(out)],check=True,stdout=subprocess.PIPE);self.assertFalse((out/'node/lib/libtest.so').is_symlink());self.assertEqual((out/'node/lib/libtest.so').read_bytes(),b'owned-test-library')
 def test_absolute_foreign_symlink_is_rejected(self):
  r,p,s=self.fixture();(p/'lib/libbad.so').symlink_to('/not-owned/something');v=subprocess.run([sys.executable,str(SCRIPT),str(p),str(s),str(r/'out')],capture_output=True,text=True);self.assertNotEqual(v.returncode,0);self.assertIn('Unreviewed absolute symlink',v.stderr)
if __name__=='__main__':unittest.main()
