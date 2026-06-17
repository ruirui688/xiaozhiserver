import sys
from pathlib import Path


SERVER_ROOT = Path(__file__).resolve().parents[1]
if str(SERVER_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVER_ROOT))

from core.semantic.frames import ASRResult, RobotContext
from core.semantic.music_intent import parse_music_intent
from core.semantic.router import SemanticRouter
from plugins_func.functions import play_music


def test_parse_sing_named_song_as_music_play():
    intent = parse_music_intent("嗯你唱一遍会员星光")

    assert intent is not None
    assert intent.action == "play"
    assert intent.song_name == "会员星光"


def test_parse_repeat_named_song_as_music_play():
    intent = parse_music_intent("嗯那你再放一遍汇源星光")

    assert intent is not None
    assert intent.action == "play"
    assert intent.song_name == "汇源星光"


def test_parse_context_repeat_uses_last_song():
    intent = parse_music_intent(
        "嗯那你给我放一遍吧",
        is_music_context=True,
        last_song_name="会员星光",
    )

    assert intent is not None
    assert intent.action == "play"
    assert intent.song_name == "会员星光"


def test_parse_generic_music_play_as_random():
    intent = parse_music_intent("给我放首歌")

    assert intent is not None
    assert intent.action == "play"
    assert intent.song_name == "random"


def test_parse_music_library_query():
    intent = parse_music_intent("你这里有什么歌")

    assert intent is not None
    assert intent.action == "library"


def test_parse_non_music_control_is_not_music():
    assert parse_music_intent("你可以把音量放大一点吗") is None


def test_parse_negative_music_action_as_stop():
    for text in ["我不听了", "我说我不听了", "别放了", "不要唱了", "不想听歌了"]:
        intent = parse_music_intent(text, is_music_context=True)

        assert intent is not None
        assert intent.action == "stop"
    assert intent.song_name == "random"


def test_negative_music_action_requires_music_scope_without_context():
    assert parse_music_intent("你怎么不听话", is_music_context=False) is None
    assert parse_music_intent("我不听歌了", is_music_context=False).action == "stop"


def test_parse_explicit_music_stop_as_stop():
    for text in ["停止播放", "停止音乐", "暂停播放", "停掉这首歌", "关闭音乐"]:
        intent = parse_music_intent(text, is_music_context=True)

        assert intent is not None
        assert intent.action == "stop"


def test_parse_short_stop_in_music_context_only():
    assert parse_music_intent("算了", is_music_context=True).action == "stop"
    assert parse_music_intent("算了", is_music_context=False) is None


def test_negative_music_action_does_not_extract_particle_as_song_name():
    intent = parse_music_intent("我不听了", is_music_context=True)

    assert intent is not None
    assert intent.action != "play"
    assert intent.song_name != "了"


def test_semantic_router_routes_music_stop_before_chat_or_play():
    router = SemanticRouter()
    frame = router.route(
        ASRResult(
            session_id="s1",
            device_id="d1",
            raw_text="我说我不听了",
            normalized_text="我说我不听了",
            display_text="我说我不听了",
        ),
        RobotContext(
            device_id="d1",
            session_id="s1",
            state="speaking",
            current_skill="music",
        ),
    )

    assert frame.full_name == "music.stop"
    assert frame.confidence >= 0.99


def test_music_metadata_aliases_match_local_song():
    play_music.MUSIC_CACHE.clear()
    play_music.MUSIC_CACHE["music_metadata"] = {
        "慧苑星光.mp3": {
            "description": "慧苑星光，慧苑主题曲。",
            "aliases": ["会员星光", "汇源星光", "会员之歌"],
        }
    }

    music_files = ["慧苑星光.mp3", "孙燕姿 - 绿光.mp3"]

    assert play_music._find_best_match("会员星光", music_files) == "慧苑星光.mp3"
    assert play_music._find_best_match("汇源星光", music_files) == "慧苑星光.mp3"
