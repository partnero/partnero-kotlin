# Partnero Android SDK

Attribute Android app users to your [Partnero](https://www.partnero.com) partners. When a user enters a partner's code, opens a partner's link or installs the app from a partner's Google Play link, the SDK claims them for that partner. Their in-app purchases then earn the partner commission, recorded by Partnero's RevenueCat integration or by your backend.

- Android 5.0 (API 21)+, Kotlin coroutines
- Depends only on `kotlinx-coroutines` and Google's Play Install Referrer library. No analytics and no background work.
- Uses only your program's **Program ID**. Never put your Partnero API key in an app.

## Before you start

In Partnero, go to your program's **Integration → Security** page and turn on **Enable mobile app claims** under **Mobile apps**. Until then, every claim fails with `CodeNotFound`. The same page has an optional claim window, in days after the app's first launch.

Copy your **Program ID** from the program's **Integration** page in Partnero. It's the same ID as in your PartneroJS snippet.

## Install

Add the dependency to your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.partnero:partnero-android:0.1.0")
}
```

## Configure

Call `configure` once at launch, before any claim, usually in `Application.onCreate`:

```kotlin
import com.partnero.Partnero

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Partnero.configure(this, programId = "YOUR_PROGRAM_ID")
    }
}
```

The first call records the date the app was first opened on this device. If the program has a claim window, Partnero rejects claims made more than that many days after it.

If your referral links use a parameter other than `ref`, such as `?aff=SAM20`, pass `referralParameter = "aff"`. Pass `baseUrl` only to point at a staging server.

## Claim a code

Pass the code the user entered, and the ID your purchases are reported under:

```kotlin
try {
    val result = Partnero.claim(code = "SAM20", appUserId = user.id)
    when (result.status) {
        ClaimResult.Status.CLAIMED -> {}         // attributed to the code's partner
        ClaimResult.Status.ALREADY_CLAIMED -> {} // was attributed already; nothing changed
    }
} catch (e: PartneroException.CodeNotFound) {
    // show "That code isn't valid"
} catch (e: PartneroException) {
    // refused; see Errors below
} catch (e: IOException) {
    // network error; let the user try again
}
```

A user can only be claimed once. Claiming again, with any code, returns `ALREADY_CLAIMED`.

The app user ID must be an email address, or letters, numbers, `-`, `_` and `.`. With RevenueCat, claim after `Purchases.sharedInstance.logIn(...)`: anonymous IDs such as `$RCAnonymousID:…` are rejected, because they change when the app is reinstalled.

If a code is used as both a partner's link key and a promotion code, say which one the user entered:

```kotlin
Partnero.claim(code = "SAM20", appUserId = user.id, codeType = CodeType.PROMOTION_CODE)
```

Without it, a code that matches two different partners fails with `CodeConflict`.

## Claim from a link

When the app opens from a referral link, such as an App Link or a custom URL scheme:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    val uri = intent.data ?: return
    lifecycleScope.launch {
        try {
            Partnero.claim(uri, appUserId = user.id)
        } catch (e: PartneroException) {
            // refused; see Errors below
        } catch (e: IOException) {
            // offline; store the code with Partnero.code(uri) and claim it later
        }
    }
}
```

Handle `onCreate`'s intent the same way. The code is read from the link's query string, such as `https://example.com/?ref=SAM20`. `claim(uri, ...)` returns `null` without calling Partnero when the link has no code. If the user isn't signed in yet, read and store the code with `Partnero.code(uri)`, and claim it after sign-in.

## Claim from a Google Play install

Partners can share a Google Play link that carries their code in the `referrer` parameter, URL-encoded:

```
https://play.google.com/store/apps/details?id=com.example.app&referrer=ref%3DSAM20
```

After sign-in, once the app user ID is known:

```kotlin
lifecycleScope.launch {
    try {
        Partnero.claimFromInstallReferrer(appUserId = user.id)
    } catch (e: PartneroException) {
        // refused; see Errors below
    } catch (e: IOException) {
        // offline; the next call tries again
    }
}
```

Avoid `runCatching` around claims: it also swallows the `CancellationException` coroutines rely on.

It reads the referrer Google Play recorded for this install and claims its code. It runs once per install: after that, and for installs without a referrer, it returns `null` without calling Partnero. If Google Play can't be reached, or the claim fails with a rate limit, a server error or a network error, the next call tries again. Any other refusal, such as an unknown code, is final for that install, so turn on mobile app claims before you release.

## RevenueCat

In Partnero, connect RevenueCat under **Payment processors** on your program's **Integration** page, so purchases, renewals and refunds are recorded automatically.

Also store the code as a subscriber attribute. If a claim never reaches Partnero, for example because the device was offline, the first purchase still finds the partner. This also needs **Enable mobile app claims** turned on.

```kotlin
Purchases.sharedInstance.setAttributes(Partnero.revenueCatAttributes("SAM20"))
```

## Errors

Refusals are thrown as subclasses of `PartneroException`:

| Exception | Meaning |
| --- | --- |
| `NotConfigured` | `configure` hasn't been called |
| `InvalidInput` | A blank or malformed value. `field` says which one, such as `CODE`, `APP_USER_ID` or `PROGRAM_ID`. Checked before any request when possible. |
| `CodeNotFound` | No active code matches, or mobile claims are off for the program |
| `CodeConflict` | The code matches more than one partner (pass a `codeType`), or the program's claim window has closed for this install |
| `RateLimited` | More than 30 claims a minute from one network for the program. Try again in a minute. |
| `Server` | Any other response. `status` is the HTTP status. |

When the request doesn't reach Partnero, the SDK throws `IOException`. Cancelling the coroutine throws `CancellationException`. The SDK never retries on its own: your app decides whether and when to try again.

## Privacy

The SDK sends Partnero the code, the app user ID, the platform and the first launch date, and nothing else. It stores the first launch date in `SharedPreferences`, and whether the install referrer was claimed in `noBackupFilesDir`. It doesn't read device identifiers or track users across apps or websites.

In your Google Play **Data safety** form, list the user ID as collected for **App functionality** if you don't already.

## Sample app

`sample/` claims typed codes, `partnerodemo://claim?ref=CODE` links and the install referrer. Build it with your Program ID:

```bash
./gradlew :sample:installDebug -Ppartnero.programId=YOUR_PROGRAM_ID
```

## License

MIT
