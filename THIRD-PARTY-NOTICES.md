# Third-party notices

The portable releases of insilicoPCR bundle the programs and libraries below. Each is distributed under
its own license, whose text is in `third_party/` (or, for the Java runtime, in `runtime/*/jdk/legal/`).
insilicoPCR itself is under the MIT License (`LICENSE`).

| Component | Version | Used for | License | Text | Source |
|---|---|---|---|---|---|
| BBTools (BBDuk, BBMerge, Tadpole) | 40.04 | baiting, merging and assembling reads (FASTQ input) | BSD-style license of the Lawrence Berkeley National Laboratory | `third_party/BBTools/license.txt` | https://sourceforge.net/projects/bbmap/ |
| NCBI BLAST+ (`blastn`, `makeblastdb`) | 2.17.0 | searching the primers | Public domain (United States Government Work), with third-party components under their own licenses, listed in it | `third_party/BLAST/LICENSE` | https://ftp.ncbi.nlm.nih.gov/blast/executables/blast+/2.17.0/ |
| nghttp2 (`nghttp2.dll`, Windows release only, shipped with BLAST+) | as shipped with BLAST+ 2.17.0 | a library of BLAST+ on Windows | MIT License | `third_party/nghttp2/COPYING` | https://github.com/nghttp2/nghttp2 |
| OpenJFX (JavaFX base, controls, graphics) | 26.0.1 | the application's window and the synthetic gel | GNU General Public License version 2, with the Classpath Exception; third-party components of javafx.graphics under their own licenses | `third_party/OpenJFX/` (`LICENSE`, `ASSEMBLY_EXCEPTION`, `ADDITIONAL_LICENSE_INFO`, `javafx.graphics/*.md`) | https://github.com/openjdk/jfx26u/tree/26.0.1-ga |
| Eclipse Temurin JDK (OpenJDK) | 26 | the Java runtime the launchers use | GNU General Public License version 2, with the Classpath Exception; third-party components under their own licenses | `runtime/linux/jdk/legal/`, `runtime/windows/jdk/legal/` | https://adoptium.net/ |
| Apache Commons CLI | 1.8.0 | the command line | Apache License 2.0 | `META-INF/LICENSE.txt` and `NOTICE.txt` inside `lib/commons-cli-1.8.0.jar` | https://commons.apache.org/proper/commons-cli/ |

The Classpath Exception lets insilicoPCR, under its own license, be linked with OpenJFX and the OpenJDK
class libraries. The source of each component is at the address given.
