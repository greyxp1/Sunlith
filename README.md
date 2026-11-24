<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/media/sunlith-logo-dark.svg" />
    <img src="docs/media/sunlith-logo.svg" alt="Sunlith logo" width="88" />
  </picture>
</p>

<h1 align="center">Sunlith</h1>

<p align="center">An Android calendar you can update with a prompt from your computer.</p>

<p align="center">
  <a href="https://github.com/SunSinD/Sunlith/releases/latest"><strong>Download for Android</strong></a><br />
  Android 8.0 or newer. No ads or analytics.
</p>

<p align="center">
  <picture><img src="docs/media/sunlith-tour.webp" alt="A computer prompt adds dinner at The Keg tomorrow at 6 PM to Sunlith on Android" width="640" /></picture>
</p>

<p align="center">
  <picture><img src="docs/images/month.png" alt="Month view in dark mode with sample classes" width="31%" /></picture>
  <picture><img src="docs/images/week.png" alt="Week timeline in dark mode with three visible days" width="31%" /></picture>
  <picture><img src="docs/images/day.png" alt="Day cards in dark mode with class times and rooms" width="31%" /></picture>
</p>

<details>
<summary>Light mode screenshots</summary>

<p align="center">
  <picture><img src="docs/media/month-light.webp" alt="Month grid in light mode with sample events" width="31%" /></picture>
  <picture><img src="docs/media/week-light.webp" alt="Week timeline in light mode with three days" width="31%" /></picture>
  <picture><img src="docs/media/day-light.webp" alt="Day cards in light mode with event times and locations" width="31%" /></picture>
</p>

</details>

## Your calendar

- **Month, Week and Day.** Swipe the bottom toolbar sideways to switch views, or up to search. Pinch Week sideways to show three, five or seven days, and pinch vertically to zoom the timeline.
- **Events and tasks.** Add reminders or repeating events, search your calendar, and mark events as tasks. See what’s coming up in the agenda widget.
- **Appearance.** Follow the system theme or choose light or dark. Sunlith uses wallpaper colors on supported Android versions and is available in English or French.

<details>
<summary>Preview the app icons</summary>

| Light | Dark |
| :---: | :---: |
| <picture><img src="docs/media/sunlith-logo.svg" alt="Light app icon" width="64" /></picture> | <picture><img src="docs/media/sunlith-logo-dark.svg" alt="Dark app icon" width="64" /></picture> |

Pick one in Settings > App icon.

</details>

## Connect your computer

Set this up once, then ask your assistant to add, move or remove events on your phone. You can type a request or attach a screenshot if your assistant supports images. A direct connection works without a Google account.

You'll need an assistant installed on your computer, such as the [Codex CLI](https://developers.openai.com/codex/cli/) or [Claude Code](https://code.claude.com/docs/en/setup).

### 1. Install Node.js

Install [Node.js](https://nodejs.org/) version 22 or newer on your computer. The phone supplies the companion in the next step.

### 2. Pair your phone

1. Put your phone and computer on the same Wi-Fi network, or turn on Tailscale on both with the same account.
2. In Sunlith, open **Settings > Computer connection**, choose the calendars to share, and tap **Share setup file**. Allow notifications if Android asks.
3. Send `sunlith-computer.zip` privately to your computer. The ZIP contains the companion and the key that grants access to the selected calendars.

### 3. Add Sunlith to your assistant

On your computer, open PowerShell on Windows or a terminal on macOS or Linux in the folder containing the ZIP. On your phone, open **Set up on your computer**, select your operating system and assistant, then use **Copy command** and run the command on your computer. For other MCP clients, the command prints the configuration to add to your client. See the [connection reference](docs/AI-INTEGRATION.md#direct-phone-connection) for manual setup.

### 4. Try it

1. Start a new session in your assistant and ask **“Which calendars can you see in Sunlith?”** The phone’s **Last request** shows when it answers.
2. Once it lists your calendar, try **“Add dinner at The Keg tomorrow at 6 PM for one hour.”** Open tomorrow in Sunlith to see the event.

For later use, keep both devices on the same network and turn on **Allow computer access** if it is off. Turn it off to pause access, or use **Unpair computer** to revoke it.

<details>
<summary>If it does not connect</summary>

- **The assistant cannot find Sunlith.** Open a new session after setup. Check the registration with `codex mcp list` or `claude mcp list`.
- **The setup command cannot find the ZIP.** Run it in the folder containing `sunlith-computer.zip` and check that the filename matches.
- **The assistant cannot reach your phone.** Check the network and **Allow computer access**. If your phone’s Wi-Fi address changed, unpair and repeat setup with a new ZIP.
- **The assistant cannot add events.** Check that the calendar is shared and that you can add an event to it on your phone. If the event was added but is hidden, check **Settings > Calendars**.

</details>

## School schedules

I built this for Col.NET at Collège Montmorency, my own college. Connect in **Settings > Calendars > School schedule** to sync classes to the on-device Sunlith calendar. Open **College messages** from the mail icon in the calendar toolbar. Turn on **School alerts** in **Settings > Notifications** for schedule changes, new messages, and sync problems.

Other Col.NET schools can be set up manually. If you’d like me to add support for your school, [open an issue and let me know](https://github.com/SunSinD/Sunlith/issues/new).

## Build and checks

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

See [calendar imports](docs/MIGRATION.md). To check for updates in the app, tap **Version** in **Settings**.
