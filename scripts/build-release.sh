#!/bin/bash
# FixTool Release Build Script
# Builds unsigned installers for all platforms

set -e

echo "========================================="
echo "FixTool Release Build"
echo "========================================="
echo ""

# Clean previous builds
echo "Cleaning previous builds..."
./gradlew clean

# Build all platform packages
echo ""
echo "Building platform packages..."
echo "This may take a few minutes..."
./gradlew packageDmg packageMsi packageDeb

# Show results
echo ""
echo "========================================="
echo "Build Complete!"
echo "========================================="
echo ""
echo "Package locations:"
echo ""

DMG_DIR="composeApp/build/compose/binaries/main/dmg"
MSI_DIR="composeApp/build/compose/binaries/main/msi"
DEB_DIR="composeApp/build/compose/binaries/main/deb"

if [ -d "$DMG_DIR" ]; then
    echo "macOS DMG:"
    ls -lh "$DMG_DIR"/*.dmg 2>/dev/null || echo "  (not built)"
    echo ""
fi

if [ -d "$MSI_DIR" ]; then
    echo "Windows MSI:"
    ls -lh "$MSI_DIR"/*.msi 2>/dev/null || echo "  (not built)"
    echo ""
fi

if [ -d "$DEB_DIR" ]; then
    echo "Linux DEB:"
    ls -lh "$DEB_DIR"/*.deb 2>/dev/null || echo "  (not built)"
    echo ""
fi

# Generate checksums
echo "Generating SHA-256 checksums..."
{
    echo "# FixTool v1.0.0 Checksums"
    echo ""
    echo "Verify your download with:"
    echo "  macOS/Linux: shasum -a 256 filename"
    echo "  Windows: Get-FileHash filename -Algorithm SHA256"
    echo ""

    if [ -f "$DMG_DIR"/*.dmg ]; then
        shasum -a 256 "$DMG_DIR"/*.dmg
    fi

    if [ -f "$MSI_DIR"/*.msi ]; then
        shasum -a 256 "$MSI_DIR"/*.msi
    fi

    if [ -f "$DEB_DIR"/*.deb ]; then
        shasum -a 256 "$DEB_DIR"/*.deb
    fi
} > CHECKSUMS.txt

echo ""
echo "Checksums saved to: CHECKSUMS.txt"
cat CHECKSUMS.txt

echo ""
echo "========================================="
echo "Next Steps:"
echo "========================================="
echo ""
echo "1. Test the installers on each platform"
echo "2. Upload to GitHub Releases"
echo "3. Include CHECKSUMS.txt in the release"
echo "4. Add INSTALLATION.md to release notes"
echo ""
echo "Note: Installers are UNSIGNED (normal for open source)"
echo "Users will see security warnings - this is expected!"
echo "See INSTALLATION.md for user instructions."
echo ""
