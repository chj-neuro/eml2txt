# EML → TXT

A tiny Android app that turns an **.eml** file into a plain **.txt** file.

- **Real emails** become readable text:
  - the header (From, To, Date, Subject, list of attachments)
  - the message text (HTML mails are converted to text)
  - the contents of text attachments (.txt, .csv, …), including ones inside .zip files and forwarded mails
  - Checkboxes choose which of these go into the .txt, and the choice is remembered.
- **Files that are only *named* .eml** but are really plain text are saved exactly as they are. Some apps export chats this way, for example KakaoTalk's chat export.
- Handles Korean names and text (UTF‑8, EUC‑KR/CP949) and other encodings. The .txt is saved as UTF‑8.
- Shows a preview. **Save as .txt** lets you choose where the file goes: Downloads, Google Drive, …
- **No internet permission.** Your files never leave the phone.
- Needs Android 8.0 or newer.

---

## Guide: from this folder to the app on your phone

You don't need Android Studio or anything else installed. GitHub builds the APK for you in the cloud, for free.

### Step 1 — Create a GitHub repository

1. Sign in at <https://github.com>.
2. Click **+** (top right) → **New repository**.
3. Fill in the form:
   - **Repository name:** `eml2txt`
   - **Private** or **Public**: either works. With Private, you must be signed in to GitHub on your phone to download the APK.
   - Leave "Add a README" **unticked**.
4. Click **Create repository**.

### Step 2 — Upload the project

**Option A: in the browser (easiest)**

1. Unzip `Eml2Txt.zip` on your computer.
2. On the new repository page, click the link **uploading an existing file**. If the repo already has files, use **Add file → Upload files** instead.
3. Open the unzipped `Eml2Txt` folder, select **everything inside it**, and drag it onto the GitHub page. Folders are fine.
4. ⚠️ Make sure the hidden **`.github`** folder is included. It holds the build instructions. File managers hide names that start with a dot:
   - Linux: press **Ctrl+H** to show them.
   - Mac Finder: press **Cmd+Shift+.** to show them.
5. Scroll down and click **Commit changes**.

**Option B: with git (Linux/Mac terminal)**

Sign in once with `gh auth login` (GitHub CLI). A personal access token also works, but it needs the **`workflow`** scope, or GitHub rejects the `.github/workflows` file.

```bash
cd Eml2Txt
git init -b main
git add .
git commit -m "First version"
git remote add origin https://github.com/YOUR-NAME/eml2txt.git
git push -u origin main
```

### Step 3 — Let GitHub build the APK (about 3–5 minutes)

1. Open the **Actions** tab of the repository. A run called **Build APK** starts by itself after the upload.
2. Wait for the result:
   - 🟡 = building
   - ✅ = done
   - ❌ = something failed. Click the run, click the red step, and copy the last ~30 lines of the log to Claude.
3. If Actions asks you to enable workflows, click the green button. Then click **Build APK → Run workflow**.

### Step 4 — Download the APK on your phone

1. On the phone, open your repository page in Chrome or Samsung Internet. For a private repo, sign in first.
2. Find **Releases** (on mobile, scroll down) → **Latest build** → tap **Eml2Txt.apk**.
3. If the browser warns that the file might be harmful, tap **Download anyway** (무시하고 다운로드).

*Alternative:* on your computer, go to **Actions** → the ✅ run → **Artifacts** → **Eml2Txt-apk**. This downloads a zip with the APK inside. Unzip it and move the APK to the phone with **Google Drive** or a **USB cable**. KakaoTalk and Gmail refuse to send .apk files.

### Step 5 — Install it

1. Tap the downloaded `Eml2Txt.apk`.
2. **Samsung Galaxy:** if the install is refused with no way to continue, **Auto Blocker** (자동 차단) is on. Go to Settings → Security and privacy (보안 및 개인정보 보호) → Auto Blocker and turn it off. Install the app, then turn Auto Blocker back on.
3. Android asks to allow installs from this source. Tap **Settings** → turn on **Allow from this source** (출처를 알 수 없는 앱 설치 허용) → go back → **Install**.
4. If **Play Protect** warns about an unknown app, tap **More details → Install anyway** (세부정보 더보기 → 무시하고 설치). It warns because the app isn't from the Play Store.

The app appears as **EML→TXT**.

### Step 6 — Use it

**Inside the app**

1. Open **EML→TXT**.
2. Tap **.eml 파일 선택** and pick the file.
3. For a real email, tick what to include:
   - **머리글**: the header
   - **메일 본문**: the message text
   - **텍스트 첨부파일**: text attachments

   To get only an attached file's text (e.g. a chat log someone emailed you), leave only **텍스트 첨부파일** ticked.
4. Check the preview, then tap **.txt로 저장** → choose a folder → **Save**.

**From another app**

- **File manager or Gmail:** tap the `.eml` → **Open with / 다른 앱으로 열기** → **EML→TXT**. If another app (e.g. Samsung Email) opens it straight away, long-press the file instead → ⋮ → **다른 앱으로 열기**.
- **Share:** in any app's share sheet, pick **EML→TXT**. It accepts files and plain text.
  - This may also work straight from KakaoTalk's chat export, which would skip saving the .eml first. Untested — try it.

**Try it first** with the made-up files in `samples/`:

- `sample_email.eml`: a normal email with attachments.
- `sample_chat_export.eml`: a chat export that is really plain text.

---

## Changing the app later

1. Edit the files, or ask Claude for changes.
2. Upload them again (Step 2). GitHub rebuilds automatically.
3. Install the new APK. It updates the existing app, because every build is signed with the same key (`app/debug.keystore`).

## Troubleshooting

| What you see | What to do |
|---|---|
| Actions run ❌ | Click the failed step, copy the error lines, and paste them to Claude. |
| No run appears in Actions | The `.github` folder probably wasn't uploaded (see Step 2, point 4). |
| Install refused on a Galaxy phone | Turn off Auto Blocker for the install (Step 5, point 2). |
| "App not installed" | An older copy signed with a different key is installed. Uninstall it, then install again. |
| "This isn't an email or text file" | The file is a picture, PDF, archive, … Only emails and text files can be converted. |
| The saved .txt looks garbled on a PC | Open it with an editor that reads UTF‑8 (Notepad, VS Code, …). The file is saved as UTF‑8. |

## What's in this folder

| Path | What it is |
|---|---|
| `app/src/main/java/.../EmlConverter.kt` | The converter: reads the email format, decodes it, builds the text. Plain Kotlin, no Android code. |
| `app/src/main/java/.../MainActivity.kt` | The screen: pick file → options → preview → save. |
| `app/src/main/res/` | Layout, texts (English + `values-ko` Korean), colors, icon. |
| `app/src/main/AndroidManifest.xml` | App name, icon, "Open with"/"Share" hooks. The app asks for no permissions. |
| `app/src/test/.../EmlConverterTest.kt` | Automatic tests. GitHub runs them before every build. |
| `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/`, `gradlew` | Build setup (Gradle). |
| `.github/workflows/build.yml` | The instructions GitHub follows to build and publish the APK. |
| `samples/` | Made-up files for trying the app. |
