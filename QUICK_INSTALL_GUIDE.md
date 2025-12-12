# FixTool Quick Install - TL;DR

## macOS

**You'll see a security warning - this is NORMAL for open source apps!**

### Fastest Way:
```bash
xattr -d com.apple.quarantine ~/Downloads/FixTool-1.0.0.dmg && open ~/Downloads/FixTool-1.0.0.dmg
```

### Or Right-Click:
```
Download → Right-click DMG → Open → Open → Install
```

---

## Windows

**If you see "Windows protected your PC":**
```
Click "More info" → "Run anyway"
```

---

## Linux

```bash
sudo dpkg -i FixTool-1.0.0.deb
fixtool
```

---

## Why the Warning?

Apple/Microsoft charge $99-$300/year to remove these warnings. As a **free, open source project**, we don't pay these fees.

**This is normal!** Major open source apps like Audacity, OBS Studio, and GIMP show the same warnings.

**FixTool is safe** - all code is public on GitHub!

---

## Need More Help?

See [INSTALLATION.md](INSTALLATION.md)
