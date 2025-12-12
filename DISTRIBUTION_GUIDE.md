# FixTool Distribution Guide (Open Source / Unsigned)

This guide explains how to build and distribute FixTool as an **unsigned, open source application**.

---

## 🎯 Philosophy

FixTool is a **free, open source project**. We do not pay for:
- Apple Developer Program ($99/year for macOS notarization)
- Microsoft Code Signing Certificate ($300+/year for Windows signing)

This means users will see security warnings when installing - **this is normal and expected** for open source software!

**Major open source projects with the same approach:**
- Audacity (audio editor)
- OBS Studio (streaming software)
- GIMP (image editor)
- VLC media player (before they got sponsored)
- Many others!

---

## 🛠️ Building Releases

### Build All Platforms

```bash
# Build DMG (macOS), MSI (Windows), DEB (Linux)
./scripts/build-release.sh
```

This will:
1. Clean previous builds
2. Build platform packages (unsigned)
3. Generate SHA-256 checksums
4. Save checksums to `CHECKSUMS.txt`

### Build Individual Platforms

```bash
# macOS only
./gradlew packageDmg

# Windows only
./gradlew packageMsi

# Linux only
./gradlew packageDeb
```

### Package Locations

After building:
- **macOS DMG:** `composeApp/build/compose/binaries/main/dmg/`
- **Windows MSI:** `composeApp/build/compose/binaries/main/msi/`
- **Linux DEB:** `composeApp/build/compose/binaries/main/deb/`

---

## 📦 Creating a GitHub Release

### 1. Tag the Release

```bash
git tag -a v1.0.0 -m "Release version 1.0.0"
git push origin v1.0.0
```

### 2. Create Release on GitHub

1. Go to: `https://github.com/yourrepo/releases/new`
2. Select tag: `v1.0.0`
3. Release title: `FixTool v1.0.0`
4. Upload files:
   - `FixTool-1.0.0.dmg` (macOS)
   - `FixTool-1.0.0.msi` (Windows)
   - `FixTool-1.0.0.deb` (Linux)
   - `CHECKSUMS.txt`

### 3. Release Notes Template

```markdown
# FixTool v1.0.0

## 🎉 Features

[List your features here]

## 📦 Installation

Download the installer for your platform below.

### ⚠️ Security Warnings (macOS & Windows)

**You will see security warnings - this is normal!**

FixTool is an unsigned, open source application. Apple and Microsoft charge $99-$300/year to remove these warnings. As a free project, we don't pay these fees.

**FixTool is safe!** All code is public and reviewable.

### macOS Installation

**You'll see:** "FixTool cannot be opened because it is from an unidentified developer"

**How to install:**
1. Download `FixTool-1.0.0.dmg`
2. **Right-click** the DMG → **Open** → **Open**
3. Drag to Applications

**OR use Terminal:**
```bash
xattr -d com.apple.quarantine ~/Downloads/FixTool-1.0.0.dmg
open ~/Downloads/FixTool-1.0.0.dmg
```

### Windows Installation

**If you see:** "Windows protected your PC"

**How to install:**
1. Click **"More info"**
2. Click **"Run anyway"**
3. Follow the installer

### Linux Installation

```bash
sudo dpkg -i FixTool-1.0.0.deb
```

## 📖 Full Installation Guide

See [INSTALLATION.md](INSTALLATION.md) for complete instructions and troubleshooting.

## 🔍 Verify Download (Advanced)

Check SHA-256 hash against `CHECKSUMS.txt`:

**macOS/Linux:**
```bash
shasum -a 256 FixTool-1.0.0.dmg
```

**Windows (PowerShell):**
```powershell
Get-FileHash FixTool-1.0.0.msi -Algorithm SHA256
```

## 📋 System Requirements

- **macOS:** 11+ (Big Sur or later)
- **Windows:** 10+ (64-bit)
- **Linux:** Ubuntu 20.04+, Debian 11+

## 🐛 Known Issues

[List any known issues]

## 🙏 Contributors

[Thank your contributors]

---

**Full Changelog:** https://github.com/yourrepo/compare/v0.9.0...v1.0.0
```

---

## 📝 Important Files to Include

Make sure these files are in your repository:

- ✅ `INSTALLATION.md` - Complete installation guide
- ✅ `QUICK_INSTALL_GUIDE.md` - TL;DR version
- ✅ `README.md` - Main readme with macOS warning section
- ✅ `CHECKSUMS.txt` - Generated during build

---

## 🔒 Security Best Practices

### Always Provide Checksums

```bash
# Generate checksums (done automatically by build-release.sh)
shasum -a 256 *.dmg *.msi *.deb > CHECKSUMS.txt
```

### Be Transparent

In all documentation:
- ✅ Explain why the app is unsigned
- ✅ Mention it's open source
- ✅ Provide installation workarounds
- ✅ Link to source code
- ✅ Show how to verify checksums

### Set Expectations

Users should know:
- Security warnings are expected
- The app is safe (open source)
- They can review the code
- Many major projects do the same

---

## 📢 Communication Tips

### For First-Time Users

**Bad:**
> "Download and install FixTool"

**Good:**
> "Download FixTool. **Note:** You'll see a security warning (normal for open source apps) - just right-click and choose Open. See [INSTALLATION.md](INSTALLATION.md) for details."

### For GitHub Issues

If users report security warnings:
```markdown
Thanks for reporting! This is expected behavior.

FixTool is an unsigned, open source application. Apple/Microsoft charge
$99-$300/year to remove these warnings, which we don't pay as a free project.

**The app is completely safe** - all code is open source and reviewable.

To install:
- **macOS:** Right-click DMG → Open → Open
- **Windows:** Click "More info" → "Run anyway"

See our [Installation Guide](INSTALLATION.md) for details.
```

---

## 🎓 FAQ for Developers

### Q: Should I get an Apple Developer account?

**A:** Not necessary for open source projects. Save the $99/year.

Many successful open source projects distribute unsigned apps:
- Audacity
- OBS Studio
- GIMP
- Inkscape
- And hundreds more!

### Q: Will users be scared by the warning?

**A:** Most tech-savvy users understand open source security warnings.

**Tips to reduce friction:**
1. Document it clearly in README
2. Compare to other well-known projects
3. Provide checksums for verification
4. Link to source code
5. Make install process as easy as possible

### Q: Can I sign for free?

**A:** No legitimate way to sign for free on macOS or Windows.

- macOS: Requires Apple Developer account ($99/year)
- Windows: Requires code signing certificate ($300+/year from CA)

### Q: What about self-signed certificates?

**A:** Don't bother - they cause even scarier warnings!

An unsigned app is better than a self-signed one. Self-signed certificates show:
- "Developer cannot be verified"
- "May be malware"
- Often can't be bypassed easily

Unsigned apps show:
- "From unidentified developer"
- Easy bypass (right-click → Open)

---

## ✅ Release Checklist

Before releasing:

- [ ] Run `./gradlew jvmTest` (all tests pass)
- [ ] Run `./scripts/build-release.sh`
- [ ] Test on clean macOS system
- [ ] Test on clean Windows system
- [ ] Test on clean Linux system
- [ ] Verify checksums match
- [ ] Update version in `build.gradle.kts`
- [ ] Update `CHANGELOG.md`
- [ ] Create git tag
- [ ] Upload to GitHub Releases
- [ ] Include `CHECKSUMS.txt`
- [ ] Add installation instructions to release notes
- [ ] Test download links
- [ ] Announce release!

---

## 🌟 Success Metrics

A successful open source release:
- ✅ Clear documentation
- ✅ Easy workaround for security warnings
- ✅ Checksums provided
- ✅ Source code linked
- ✅ Installation tested
- ✅ Community informed

**Downloads matter more than signed status!**

---

## 📚 Additional Resources

- [Distributing Unsigned macOS Apps](https://developer.apple.com/forums/thread/128166)
- [Open Source Code Signing Discussion](https://github.com/electron/electron/issues/7476)
- [Why We Don't Sign (Audacity)](https://github.com/audacity/audacity/wiki/Signing)

---

**Remember:** Being unsigned doesn't mean insecure. Open source means users can verify the code themselves - that's more secure than blind trust in a signature!
