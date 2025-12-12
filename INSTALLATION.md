# FixTool Installation Guide

## 📦 Download

Download the latest version for your platform:
- **macOS:** `FixTool-1.0.0.dmg`
- **Windows:** `FixTool-1.0.0.msi` or `FixTool-1.0.0.exe`
- **Linux:** `FixTool-1.0.0.deb`

---

## 🍎 macOS Installation

### Security Warning - This is Normal!

You'll see: **"FixTool cannot be opened because it is from an unidentified developer"**

**This is expected and safe!** FixTool is an **open source project** and doesn't pay for Apple's $99/year notarization service. Many open source apps show this warning.

### ✅ How to Install (Choose One)

#### **Method 1: Right-Click to Open** ⭐ RECOMMENDED

1. Download `FixTool-1.0.0.dmg`
2. **Right-click** (or Control+click) on the DMG file
3. Select **"Open"** from the menu
4. Click **"Open"** in the dialog
5. Drag FixTool to Applications

**Visual Guide:**
```
[Finder] → [Right-click DMG] → [Open] → [Click "Open"] → [Install]
```

#### **Method 2: Terminal Command** ⚡ FASTEST

Open Terminal and paste:

```bash
xattr -d com.apple.quarantine ~/Downloads/FixTool-1.0.0.dmg && open ~/Downloads/FixTool-1.0.0.dmg
```

Then drag FixTool to Applications.

#### **Method 3: System Settings** (macOS 13+)

1. Double-click the DMG (it will be blocked)
2. Open **System Settings** → **Privacy & Security**
3. Scroll to find: *"FixTool was blocked..."*
4. Click **"Open Anyway"**
5. Click **"Open"** to confirm

---

## 🪟 Windows Installation

### **MSI Installer** (Recommended)

1. Download `FixTool-1.0.0.msi`
2. Double-click to run
3. Follow the wizard
4. Done!

### Windows Defender SmartScreen Warning

If you see **"Windows protected your PC"**:
1. Click **"More info"**
2. Click **"Run anyway"**

**Why?** Same reason as macOS - we're an open source project and don't pay $300/year for a code signing certificate. The app is safe!

---

## 🐧 Linux Installation

### Debian/Ubuntu (.deb)

```bash
# Install
sudo dpkg -i FixTool-1.0.0.deb

# Fix dependencies if needed
sudo apt-get install -f

# Run
fixtool
```

### Make Executable (if needed)

```bash
chmod +x /opt/fixtool/bin/FixTool
```

---

## 🔒 Is FixTool Safe?

### ✅ YES! Here's Why:

1. **Open Source:** All code is public on GitHub - review it yourself
2. **No Telemetry:** We don't track you or send any data
3. **No Network Access:** Except for FIX connections you configure
4. **Local Storage:** All your data stays on your machine

### Why the Security Warnings?

**macOS:** Apple charges $99/year to "notarize" apps
**Windows:** Microsoft charges $300/year for code signing certificates

As a **free, open source project**, we don't pay these fees. This is normal and accepted in the open source community!

**Examples of major open source projects with similar warnings:**
- Audacity (audio editor)
- OBS Studio (streaming software)
- GIMP (image editor)
- Many others!

---

## 🔍 Verify the Download (Advanced)

If you want extra security, verify the checksum:

### macOS/Linux
```bash
shasum -a 256 FixTool-1.0.0.dmg
```

### Windows (PowerShell)
```powershell
Get-FileHash FixTool-1.0.0.msi -Algorithm SHA256
```

Compare with the checksum in `CHECKSUMS.txt` from the release page.

---

## 🛠️ Troubleshooting

### macOS: "FixTool is damaged and can't be opened"

**Solution:**
```bash
sudo xattr -cr /Applications/FixTool.app
```

### macOS: After install, app won't start

**Solution:**
```bash
# Remove quarantine from the app bundle
xattr -cr /Applications/FixTool.app

# Try opening from Terminal to see error messages
open /Applications/FixTool.app
```

### Windows: "This app can't run on your PC"

**Solution:** Make sure you downloaded the 64-bit version (x64).

### Linux: "fixtool: command not found"

**Solution:**
```bash
# Add to PATH
export PATH=$PATH:/opt/fixtool/bin

# Or run directly
/opt/fixtool/bin/FixTool
```

---

## 📋 System Requirements

### macOS
- **OS:** macOS 11 (Big Sur) or later
- **RAM:** 2GB minimum, 4GB recommended
- **Disk:** 200MB free space

### Windows
- **OS:** Windows 10 or later (64-bit)
- **RAM:** 2GB minimum, 4GB recommended
- **Disk:** 200MB free space

### Linux
- **Distributions:** Ubuntu 20.04+, Debian 11+, Fedora 35+
- **Architecture:** x86_64 (AMD64)
- **RAM:** 2GB minimum, 4GB recommended
- **Disk:** 200MB free space

---

## 🆘 Need Help?

- **Documentation:** [GitHub Wiki](https://github.com/yourrepo/wiki)
- **Report Issues:** [GitHub Issues](https://github.com/yourrepo/issues)
- **Discussions:** [GitHub Discussions](https://github.com/yourrepo/discussions)

---

## 📖 Next Steps

After installation:
1. Launch FixTool
2. Configure your first FIX connection profile
3. Check out the [User Guide](USER_GUIDE.md) (if available)

---

**FixTool is free and open source - enjoy!** 🎉
