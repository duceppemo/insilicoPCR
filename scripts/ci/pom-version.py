"""The project's own version in a pom.xml (not a parent's or a dependency's): python3 -I pom-version.py pom.xml"""
import sys
import xml.etree.ElementTree as ET

NS = {"m": "http://maven.apache.org/POM/4.0.0"}
project = ET.parse(sys.argv[1]).getroot()
version = project.find("m:version", NS)
if version is None or not (version.text or "").strip():
    sys.exit(f"{sys.argv[1]}: no project version")
print(version.text.strip())
