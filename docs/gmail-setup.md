# Connecting Gmail

Deputy reads mail read-only, and only from one Gmail label (D25). One-time setup, about ten
minutes, on your own Google account.

## 1. Google Cloud project

1. In the [Google Cloud console](https://console.cloud.google.com/), create a project (any name,
   e.g. "Deputy").
2. **APIs & Services → Library**: enable the **Gmail API**.
3. **Google Auth Platform → Branding / Audience** (older consoles: **OAuth consent screen**):
   user type **External**, publishing status **Testing**, and add your own Gmail address under
   **Test users**. Fill in only the required fields.
4. **Data access**: add the scope `https://www.googleapis.com/auth/gmail.readonly`.

gmail.readonly is a *restricted* scope. Publishing an app with it needs Google's security
review, but in Testing mode it works for listed test users without one. Testing-mode grants
expire after 7 days, so expect to reconnect weekly.

## 2. Android OAuth client

Get the SHA-1 of the debug keystore the app is signed with:

```
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Then **Clients → Create client**, type **Android**:

- Package name: `io.github.greg7gkb.deputy`
- SHA-1 certificate fingerprint: the `SHA1:` line from above

No client id or secret goes into the app: Play Services matches the app by package and
signature. A build signed with a different key (another machine's debug keystore, a release
key) needs its own SHA-1 added to the same client.

## 3. The label

In Gmail, create a label named **Deputy** (or set `deputy.mailLabel=...` in `local.properties`
and rebuild). Put a few test messages under it, by hand or with a filter (e.g. "from:me
subject:[deputy]" → apply label). Deputy reads nothing outside that label.

## 4. Connect

Install the debug build on a phone or emulator with Google Play and your account signed in,
then **Lab → Connect Gmail** and approve the consent screen.

## What the label does and doesn't protect

Google can't scope a grant to a label: the gmail.readonly token can read the whole mailbox.
The label restriction is Deputy's own code, enforced in `GmailAdapter`, which lists only
messages carrying the label, re-checks the label on every message it fetches, and keeps only
From, Subject, the received time and the plain-text body (D9). To withdraw access entirely,
remove Deputy at [myaccount.google.com/connections](https://myaccount.google.com/connections).
