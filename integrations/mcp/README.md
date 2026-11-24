# Sunlith MCP companion

Manage calendars on your Sunlith phone from your computer over WiFi or Tailscale. It needs no Google account or second calendar app.

The companion uses standard input/output. Sunlith runs a private HTTPS server while Computer connection is on. Pairing checks the phone certificate and grants access to calendars you select. Stop pauses access. Unpair revokes it.

Requires Node.js 22 or later. It has no dependencies to install. See [setup and client configurations](../../docs/AI-INTEGRATION.md).

It provides `list_calendars`, `list_events`, `get_event`, `create_event`, `update_event`, `delete_event`, `get_current_time` and `import_calendar_file` for imports you request from a private ICS file. IDs are Android calendar and event IDs.

It uses the phone clock and time zone for `get_current_time`. Read an event's current `etag` before updating or deleting it. Repeating events require `seriesScope: "series"`. Editing one occurrence or a series with exceptions is blocked.
