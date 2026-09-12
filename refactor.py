import os
import re

file_path = r"d:\projects\BossConsole\composeApp\src\desktopTest\kotlin\ai\rever\boss\fluck\FluckTabInfoTest.kt"

with open(file_path, "r", encoding="utf-8") as f:
    content = f.read()

# Replace val tabInfo with var tabInfo (and other variables)
content = re.sub(r'\bval (tabInfo|original|copied|tab1|tab2|tab3)\b', r'var \1', content)

# Replace .navigateToPage(...) with = ...updateNavigation(...)
content = re.sub(r'(\b(?:tabInfo|original|copied|tab1|tab2|tab3)\b)\.navigateToPage\(([^)]+)\)', r'\1 = \1.updateNavigation(\2)', content)

# Replace .navigateBack() with = ...goBack()
content = re.sub(r'(\b(?:tabInfo|original|copied|tab1|tab2|tab3)\b)\.navigateBack\(\)', r'\1 = \1.goBack()', content)

# Replace .navigateForward() with = ...goForward()
content = re.sub(r'(\b(?:tabInfo|original|copied|tab1|tab2|tab3)\b)\.navigateForward\(\)', r'\1 = \1.goForward()', content)

with open(file_path, "w", encoding="utf-8") as f:
    f.write(content)

print("Refactored FluckTabInfoTest.kt")
