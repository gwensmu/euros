"""The owner's local time. Every local date and wall-clock time in the system means this zone."""
from datetime import datetime
from zoneinfo import ZoneInfo

ZONE = ZoneInfo('America/Chicago')


def local_day(timestamp):
    """The owner's local calendar date of an ISO timestamp."""
    return datetime.fromisoformat(timestamp).astimezone(ZONE).date()


def local_clock(timestamp):
    """The owner's local HH:MM of an ISO timestamp."""
    return datetime.fromisoformat(timestamp).astimezone(ZONE).strftime('%H:%M')


def local_today():
    return datetime.now(ZONE).date()
