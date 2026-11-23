LanguageTool 6.8.27 language bundles that Phantom Grammar (grammar/) downloads when a language is enabled.

They are built by JetBrains from https://github.com/JetBrains/languagetool (version 6.8.27) for IntelliJ, and the
files are unchanged, so their MD5 checksums are those in LanguageToolDescriptor.kt. LanguageTool itself (the
`language-<iso>` modules) is under the GNU Lesser General Public License 2.1 (LICENSE-LGPL-2.1.txt). Its sources are
the language-*-sources.jar and languagetool-core-*-sources.jar files of this release and that repository.

Not everything in a bundle is LGPL. Several bundles embed other libraries and language data under their own licenses,
listed below from the POM files inside each jar. The spelling dictionaries keep their README and license files inside
the jar, under `org/languagetool/resource/<iso>/`; those files name the authors and the licenses of each dictionary.

| Bundle | Embedded component | License | Source |
|---|---|---|---|
| ast | org.languagetool:asturian-pos-dict | GPL 3.0 | https://github.com/esbardu/asturian-pos-dict |
| ca | org.softcatala:catalan-pos-dict | GPL 2.0 / LGPL 2.1 | https://github.com/softcatala/catalan-dict-tools |
| de | de.danielnaber:german-pos-dict | CC BY-SA 4.0 | https://github.com/languagetool-org/german-pos-dict |
| de | de.danielnaber:jwordsplitter | Apache 2.0 | https://github.com/danielnaber/jwordsplitter |
| de, ru | edu.washington.cs.knowitall:openregex | LGPL | https://github.com/knowitall/openregex |
| de | org.apache.commons:commons-lang3 | Apache 2.0 | https://commons.apache.org/proper/commons-lang/ |
| el | org.ioperm:morphology-el | Apache 2.0 and CC BY-SA 4.0 | http://svn.ioperm.org/morphology-el/tags/morphology-el-1.0.0 |
| en | org.languagetool:english-pos-dict | LGPL 2.1 (word lists: SCOWL and others, see the READMEs in the jar) | https://github.com/languagetool-org/english-pos-dict |
| en | org.apache.opennlp:opennlp-tools | Apache 2.0 | https://opennlp.apache.org/ |
| en | edu.washington.cs.knowitall:opennlp-{tokenize,postag,chunk}-models | Apache 2.0 according to their POM. The OpenNLP 1.5 models were trained on corpora whose terms are not stated | http://opennlp.sourceforge.net/models-1.5/ |
| es | org.softcatala:spanish-pos-dict | LGPL 2.1 | https://github.com/jaumeortola/spanish-dict-tools |
| fr | org.languagetool:french-pos-dict | CC BY-SA 4.0 | https://github.com/languagetool-org/french-pos-dict |
| ga | io.github.jimregan:languagetool-ga-dicts | GPL 2.0 | https://github.com/jimregan/languagetool-ga-dicts |
| ga | GaelSpell spelling dictionary (Kevin Scannell) | GPL 2.0 | http://borel.slu.edu/ |
| it | Italian spelling dictionary (dict-it 3.3.1, Andrea Pescetti and others) | GPL 3.0 | https://github.com/languagetool-org/languagetool/tree/v6.8/languagetool-language-modules/it/src/main/resources/org/languagetool/resource/it/hunspell |
| ja | lucene-gosen with the IPADIC dictionary, and the Lucene and Solr classes it uses | LGPL 2.1 / IPADIC license; Lucene and Solr Apache 2.0 | https://github.com/lucene-gosen/lucene-gosen |
| ja | com.ibm.icu:icu4j | Unicode License V3 | https://github.com/unicode-org/icu |
| nl | org.languagetool:dutch-pos-dict | LGPL 2.1 | https://github.com/languagetool-org/dutch-dict-tools |
| pt | org.languagetool:portuguese-pos-dict | LGPL 2.1 | https://github.com/languagetool-org/portuguese-dict-tools |
| ro | Romanian spelling dictionary | GPL / LGPL / MPL (COPYING.* in the jar) | see README_EN.txt in the jar |
| tl | Tagalog spelling dictionary (myspell-tl 0.02, Ramil Sagum and Kevin P. Scannell) | GPL 2.0 or later | https://github.com/languagetool-org/languagetool/tree/v6.8/languagetool-language-modules/tl/src/main/resources/org/languagetool/resource/tl/hunspell |
| zh | com.hankcs:hanlp | Apache 2.0 | https://github.com/hankcs/HanLP |

The other spelling dictionaries (da, de, el, eo, gl, km, nl, pl, ru, sk, sl, sv, uk, ...) are under the free licenses
named in their README files inside the jar, mostly GPL, LGPL or MPL.

## Sources of the embedded components

Besides the sources jars of LanguageTool, this release carries the sources of the copyleft components the bundles
embed (tools/attach-sources.sh):

| File | Source of |
|---|---|
| openregex-1.1.1-sources.jar | openregex (de, ru) |
| lucene-gosen-6.2.1-sources.jar | lucene-gosen (ja) |
| english-pos-dict-2c68994244.tar.gz | english-pos-dict 0.6 (en): https://github.com/languagetool-org/english-pos-dict at the commit that made 0.6 |
| asturian-pos-dict-c587ea3843.tar.gz | asturian-pos-dict 0.1 (ast): https://github.com/esbardu/asturian-pos-dict at the commit that made 0.1 |
| catalan-dict-tools-753d62b18d.tar.gz | catalan-pos-dict 3.3 (ca): https://github.com/Softcatala/catalan-dict-tools at the commit that made 3.3 |
| spanish-dict-tools-3d1f700c82.tar.gz | spanish-pos-dict 2.5 (es): https://github.com/jaumeortola/spanish-dict-tools at the commit that made 2.5 |
| languagetool-ga-dicts-2a127b3d07.tar.gz | languagetool-ga-dicts 0.02 (ga): https://github.com/jimregan/languagetool-ga-dicts at the commit that made 0.02 |
| dict_uk-v6.8.0.tar.gz | morfologik-ukrainian-lt 6.8.0 (uk): https://github.com/brown-uk/dict_uk, tag v6.8.0 |
| dictionaries-libreoffice-7.0.0.1-it_IT.tar.gz | Italian spelling dictionary dict-it 3.3.1 (it) |
| dictionaries-libreoffice-7.0.0.1-da_DK.tar.gz | Danish spelling dictionary (da) |
| dictionaries-libreoffice-7.0.0.1-sl_SI.tar.gz | Slovenian spelling dictionary (sl) |
| dictionaries-libreoffice-6.0.0.1-gl.tar.gz | Galician spelling dictionary 12.10 (gl) |

The `dictionaries-*` files are the Hunspell files of that language in the LibreOffice dictionaries
(https://git.libreoffice.org/dictionaries) at the LibreOffice release whose README is the one inside the bundle; the
bundle's `.dict` file was converted from them. For the other dictionary data (the Dutch and Portuguese part-of-speech
dictionaries, whose upstream repositories are not public, and spelling dictionaries whose exact upstream version is
not recorded, such as the ar, ast, br, de, el, eo, fr, ga, km, pl, ro, ru, sk, sv, ta and tl spelling data) NOTICE's
written offer applies: Phantom contributors provide the complete corresponding source on request through
https://github.com/newkayak12/Phantom/issues. A `.dict` file can also be dumped back to its word list with
morfologik-tools (`fsa_dump`).

License texts attached: LICENSE-LGPL-2.1.txt, LICENSE-LGPL-3.0.txt, LICENSE-GPL-2.0.txt, LICENSE-GPL-3.0.txt,
LICENSE-MPL-2.0.txt, LICENSE-CC-BY-SA-4.0.txt, LICENSE-Unicode-3.0.txt, LICENSE-Apache-2.0.txt.
