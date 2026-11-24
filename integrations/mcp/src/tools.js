import { z } from 'zod';
import { isAbsolute } from 'node:path';

function isTimeZone(value) {
  try { new Intl.DateTimeFormat('en', { timeZone: value }); return true; }
  catch { return false; }
}

function isDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(parsed.valueOf()) && parsed.toISOString().slice(0, 10) === value;
}

// Rejects offsets that do not match the zone at that local time, such as DST gaps.
function sameLocalClock(value, zone) {
  try {
    const expected = value.match(/^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})/).slice(1).map(Number);
    const parts = new Intl.DateTimeFormat('en-CA', { timeZone: zone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' }).formatToParts(new Date(value));
    return ['year', 'month', 'day', 'hour', 'minute', 'second'].every((name, index) => Number(parts.find(part => part.type === name)?.value) === expected[index]);
  } catch { return false; }
}

const dateTime = z.string().max(40).regex(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/, 'Use RFC3339 with seconds and a timezone offset.').refine(value => {
  const parts = value.match(/T(\d{2}):(\d{2}):(\d{2})/);
  return isDate(value.slice(0, 10)) && Number(parts[1]) < 24 && Number(parts[2]) < 60 && Number(parts[3]) < 60 && Number.isFinite(Date.parse(value));
}, 'Use a valid date and time.');
const timeZone = z.string().min(1).max(100).refine(isTimeZone, 'Use an IANA timezone, such as America/Toronto.');
const timed = z.object({ dateTime, timeZone }).strict().superRefine((value, ctx) => {
  if (!sameLocalClock(value.dateTime, value.timeZone)) ctx.addIssue({ code: 'custom', path: ['dateTime'], message: 'The UTC offset must be valid for this IANA timezone at this local time. Check DST gaps and offsets.' });
});
const eventTime = z.union([timed, z.object({ date: z.string().refine(isDate, 'Use a real date in YYYY-MM-DD format.') }).strict()]);
const etag = z.string().min(1).max(512).refine(value => !/[\r\n]/.test(value), 'Invalid etag.');
const recurrence = z.array(z.string().max(500).regex(/^RRULE:FREQ=(?:DAILY|WEEKLY|MONTHLY|YEARLY)(?:;[A-Z]+=[A-Z0-9,+\-]+)*$/, 'Only RRULE with DAILY, WEEKLY, MONTHLY, or YEARLY frequency is supported.')).max(1);
const reminders = z.array(z.number().int().min(0).max(40320)).max(5);
const title = z.string().trim().min(1).max(200);
const description = z.string().max(5000).optional();
const location = z.string().max(1000).optional();
const id = z.string().regex(/^[1-9][0-9]{0,18}$/, 'Use the exact positive decimal device ID returned by Sunlith.');
const pageToken = z.string().max(10).regex(/^(0|[1-9][0-9]*)$/, 'Use the numeric page token returned by Sunlith.').optional();
const limit = z.number().int().min(1).max(100).default(50);
const seriesScope = z.literal('series').optional().describe('Required to change or delete a repeating series. Listing returns occurrences with the same master ID; individual occurrences cannot be changed.');

export const schemas = {
  list_calendars: z.object({ pageToken, limit }).strict(),
  list_events: z.object({
    calendarId: id,
    timeMin: dateTime.describe('Exclusive lower bound on event end: only events ending after this instant.'),
    timeMax: dateTime.describe('Exclusive upper bound on event start: only events starting before this instant.'),
    query: z.string().max(300).optional(), pageToken, limit,
    eventTypes: z.array(z.literal('default')).length(1).default(['default'])
  }).strict(),
  get_event: z.object({ calendarId: id, eventId: id }).strict(),
  create_event: z.object({
    calendarId: id,
    operationId: z.string().min(8).max(128).regex(/^[A-Za-z0-9._-]+$/).describe('A unique operation ID. Reuse the SAME ID and details when retrying this creation.'),
    title, description, location, start: eventTime,
    end: eventTime.describe('End is exclusive. For a one-day all-day event, use the following date.'),
    recurrence: recurrence.optional(),
    reminderMinutes: reminders.optional().describe('Popup reminder offsets in minutes. On creation, omitted or empty means no reminders; on update, omitted preserves existing reminders and empty removes them.')
  }).strict(),
  update_event: z.object({
    calendarId: id, eventId: id, etag, seriesScope, title: title.optional(), description, location,
    start: eventTime.optional(), end: eventTime.optional(), recurrence: recurrence.optional(), reminderMinutes: reminders.optional()
  }).strict(),
  delete_event: z.object({ calendarId: id, eventId: id, etag, seriesScope, confirm: z.literal(true) }).strict(),
  get_current_time: z.object({ timeZone: timeZone.optional().describe('Omit to use the phone timezone and clock.') }).strict(),
  import_calendar_file: z.object({ calendarId: id, filePath: z.string().max(4096).refine(isAbsolute, 'Use an absolute path to a private ICS file outside the repository.'), confirm: z.literal(true) }).strict()
};

export const descriptions = {
  list_calendars: 'List calendars allowed by the paired Sunlith phone. Calendar IDs are decimal strings; use defaultCalendarId if available.',
  list_events: 'List phone events ending after timeMin and starting before timeMax. Repeating occurrences share their master event ID. Individual occurrences cannot be changed; get_event returns the master. Only default eventTypes are supported.',
  get_event: 'Read a phone event or repeating master by its exact decimal ID. Read the current etag before editing.',
  create_event: 'Create an event directly on the paired phone. Reuse the same operationId and details for safe retries. No guests or invitation emails.',
  update_event: 'Update a phone event with its current etag. Repeating events require seriesScope:"series" and change the whole series. Individual occurrences cannot be changed.',
  delete_event: 'Delete an exact phone event with its current etag and confirm:true after the user requests it. Repeating events require seriesScope:"series" and delete the whole series.',
  get_current_time: 'Read the phone clock, current local and UTC time. Omitting timeZone uses the phone timezone.',
  import_calendar_file: 'Import a complete private local ICS file into an allowed phone calendar. Use an absolute filePath outside the repository and confirm:true after the user requests the import. Returns imported, duplicate and skipped counts; review every skipped entry.'
};
