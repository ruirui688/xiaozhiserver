from datetime import datetime, timezone, timedelta

from core.utils import current_time


def test_current_date_uses_beijing_timezone():
    beijing_tz = timezone(timedelta(hours=8))
    before = datetime.now(beijing_tz).strftime("%Y-%m-%d")
    actual = current_time.get_current_date()
    after = datetime.now(beijing_tz).strftime("%Y-%m-%d")

    assert actual in {before, after}


def test_current_datetime_marks_beijing_time():
    value = current_time.get_current_datetime()

    assert value.endswith(" 北京时间")
