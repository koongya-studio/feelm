"""Regression tests for presentation source paths; standard library only."""
import tempfile
import unittest
from pathlib import Path
from xml.sax.saxutils import escape, quoteattr
from zipfile import ZipFile

from verify_publication import check_pptx_paths

DRAWING = 'http://schemas.openxmlformats.org/drawingml/2006/main'


def paragraph(*runs):
    return '<a:p>' + ''.join('<a:r><a:rPr lang="en-US"/><a:t>' + escape(run)
                             + '</a:t></a:r>' for run in runs) + '</a:p>'


class PresentationPathsTest(unittest.TestCase):
    def check_xml(self, xml, reject=False, member='ppt/notesSlides/notesSlide1.xml'):
        with tempfile.TemporaryDirectory(prefix='publication-path-test-') as folder:
            path = Path(folder) / 'fixture.pptx'
            with ZipFile(path, 'w') as archive:
                archive.writestr(member, xml)
            if reject:
                with self.assertRaisesRegex(ValueError, 'Machine-specific path in PPTX'):
                    check_pptx_paths(path)
            else:
                check_pptx_paths(path)

    def check_body(self, body, reject=False):
        self.check_xml(f'<notes xmlns:a="{DRAWING}">{body}</notes>', reject)

    def test_https_accepts_every_run_split_with_formatting(self):
        url = 'https://example.org/evidence.csv?x=1&y=2'
        for cut in range(1, len(url)):
            with self.subTest(cut=cut):
                self.check_body(paragraph(url[:cut], url[cut:]))

    def test_split_local_paths_rejected_after_preceding_paragraph(self):
        for path in ['C:/private/source.csv', r'\\host\share\source.csv', 'file:///private/source.csv']:
            for cut in range(1, len(path)):
                with self.subTest(path=path, cut=cut):
                    self.check_body(paragraph('Source') + paragraph(path[:cut], path[cut:]), True)

    def test_paragraph_boundary_does_not_create_path(self):
        self.check_body(paragraph('Vitamin C') + paragraph(':/ emoticon'))

    def test_line_break_does_not_create_path(self):
        self.check_body('<a:p><a:r><a:t>Vitamin C</a:t></a:r><a:br/>'
                        '<a:r><a:t>:/ emoticon</a:t></a:r></a:p>')

    def test_path_after_line_break_rejected(self):
        self.check_body('<a:p><a:r><a:t>Source</a:t></a:r><a:br/>'
                        '<a:r><a:t>C:/private/source.csv</a:t></a:r></a:p>', True)

    def test_xml_entities_decoded(self):
        self.check_body('<a:p><a:r><a:t>&#67;&#58;&#92;private</a:t></a:r></a:p>', True)

    def test_text_outside_paragraph_rejected(self):
        self.check_body('<description>C:/private/source.csv</description>', True)

    def test_tail_outside_paragraph_rejected(self):
        self.check_body('<description/>C:/private/source.csv', True)

    def test_extension_metadata_rejected_without_splitting_rendered_runs(self):
        for text, reject in [('C:/private/source.csv', True), ('https://example.org/source.csv', False)]:
            with self.subTest(text=text):
                extension = ('<a:extLst><a:ext uri="urn:publication-source">'
                             '<custom:source xmlns:custom="urn:source">' + escape(text)
                             + '</custom:source></a:ext></a:extLst>')
                self.check_body('<a:p><a:pPr>' + extension + '</a:pPr>'
                                '<a:r><a:t>http</a:t></a:r><a:r><a:rPr>' + extension
                                + '</a:rPr><a:t>s://example.org/report.csv</a:t></a:r></a:p>', reject)

    def test_relationship_targets(self):
        for target, reject in [('https://example.org/source.csv', False),
                               ('C:/private/source.csv', True),
                               (r'\\host\share\source.csv', True),
                               ('file:///private/source.csv', True)]:
            with self.subTest(target=target):
                self.check_xml('<Relationships><Relationship Target=' + quoteattr(target)
                               + '/></Relationships>', reject, 'ppt/_rels/presentation.xml.rels')


if __name__ == '__main__':
    unittest.main()
