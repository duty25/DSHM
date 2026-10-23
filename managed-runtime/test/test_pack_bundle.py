import importlib.util, json, pathlib, tempfile, unittest, zipfile
module_path=pathlib.Path(__file__).resolve().parents[1]/'tools'/'pack-bundle.py'
spec=importlib.util.spec_from_file_location('pack_bundle',module_path)
packer=importlib.util.module_from_spec(spec);spec.loader.exec_module(packer)

class BundleTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.base=pathlib.Path(self.temp.name);self.tree=self.base/'tree';self.tree.mkdir()
        for name,data in {'node/bin/node':'ELF fixture','node_modules/@deepseek-ai/dsh/lib/bin.js':'CLI fixture','node_modules/@deepseek-ai/dsh/package.json':'{"version":"0.1.7-rc.1"}','bootstrap/guest-guard.mjs':'// guard'}.items():
            p=self.tree/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(data)
    def tearDown(self):self.temp.cleanup()
    def build(self):
        output=self.base/'runtime.zip';packer.build(self.tree,output,'linux017-test');return output
    def test_code_bundle_and_external_checksum(self):
        output=self.build()
        with zipfile.ZipFile(output) as z:
            m=json.loads(z.read('manifest.json'));self.assertEqual(m['dshVersion'],'0.1.7-rc.1');self.assertEqual(len(m['files']),4)
        self.assertTrue(output.with_suffix('.zip.sha256').is_file())
    def test_credentials_are_rejected(self):
        (self.tree/'.env').write_text('fixture, not a real secret')
        with self.assertRaises(ValueError):self.build()
    def test_outside_link_is_rejected(self):
        (self.base/'outside').write_text('outside');(self.tree/'link').symlink_to('../outside')
        with self.assertRaises(ValueError):self.build()
    def test_internal_links_are_manifest_members_not_zip_links(self):
        (self.tree/'node/bin/alias').symlink_to('node');output=self.build()
        with zipfile.ZipFile(output) as z:
            m=json.loads(z.read('manifest.json'));self.assertEqual(m['symlinks'],[{'path':'node/bin/alias','target':'node'}]);self.assertNotIn('node/bin/alias',z.namelist())
if __name__=='__main__':unittest.main()
