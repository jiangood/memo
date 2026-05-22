# Logo Redesign Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current "LM" monogram and "A" glyph launcher icons with a "Thinker" head silhouette design

**Architecture:** Two Android vector drawables (foreground + monochrome) share the same head+three-dots geometry but use different colors. The adaptive icon XML and background color stay unchanged.

**Tech Stack:** Android VectorDrawable (XML), API 26+

---

### Task 1: Update foreground icon drawable

**Files:**
- Modify: `app/src/main/res/drawable/ic_launcher_foreground.xml`

- [ ] **Step 1: Replace the foreground vector drawable**

Replace the entire file content with the new "Thinker head" design:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M54,24C39,24 26,36 26,51c0,10 5,18 10,23v12h36V74c5,-5 10,-13 10,-23C82,36 69,24 54,24Z"
        android:strokeWidth="4"
        android:strokeColor="#FFFFFF"
        android:fillColor="#00000000" />
    <path
        android:pathData="M54,44m-4,0a4,4 0,1 1,8 0a4,4 0,1 1,-8 0"
        android:fillColor="#FFFFFF" />
    <path
        android:pathData="M40,50m-3,0a3,3 0,1 1,6 0a3,3 0,1 1,-6 0"
        android:fillColor="#FFFFFF" />
    <path
        android:pathData="M68,50m-3,0a3,3 0,1 1,6 0a3,3 0,1 1,-6 0"
        android:fillColor="#FFFFFF" />
</vector>
```

- [ ] **Step 2: Verify the file reads correctly**

Run: `cat app/src/main/res/drawable/ic_launcher_foreground.xml`
Expected: Shows the complete vector drawable with correct path data

### Task 2: Update monochrome icon drawable

**Files:**
- Modify: `app/src/main/res/drawable/ic_launcher_monochrome.xml`

- [ ] **Step 1: Replace the monochrome vector drawable**

Replace with the same geometry using `#000000` (system tints it for themed icons):

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M54,24C39,24 26,36 26,51c0,10 5,18 10,23v12h36V74c5,-5 10,-13 10,-23C82,36 69,24 54,24Z"
        android:strokeWidth="4"
        android:strokeColor="#000000"
        android:fillColor="#00000000" />
    <path
        android:pathData="M54,44m-4,0a4,4 0,1 1,8 0a4,4 0,1 1,-8 0"
        android:fillColor="#000000" />
    <path
        android:pathData="M40,50m-3,0a3,3 0,1 1,6 0a3,3 0,1 1,-6 0"
        android:fillColor="#000000" />
    <path
        android:pathData="M68,50m-3,0a3,3 0,1 1,6 0a3,3 0,1 1,-6 0"
        android:fillColor="#000000" />
</vector>
```

- [ ] **Step 2: Verify consistency with foreground**

Run: `diff <(grep pathData app/src/main/res/drawable/ic_launcher_foreground.xml | sed 's/strokeColor="[^"]*"//g; s/fillColor="[^"]*"//g') <(grep pathData app/src/main/res/drawable/ic_launcher_monochrome.xml | sed 's/strokeColor="[^"]*"//g; s/fillColor="[^"]*"//g')`
Expected: No output (identical pathData)

### Task 3: Commit

- [ ] **Step 1: Stage and commit**

```bash
git add app/src/main/res/drawable/ic_launcher_foreground.xml app/src/main/res/drawable/ic_launcher_monochrome.xml docs/superpowers/
git commit -m "feat: redesign app icon to Thinker head silhouette

Replace the LM monogram and A-glyph icons with a rounded head
silhouette featuring three dots (crown + two eyes), symbolizing
thought and insight.

Foreground: white strokes on transparent (black bg in adaptive icon)
Monochrome: black strokes (system-tinted for themed icons)"
```
