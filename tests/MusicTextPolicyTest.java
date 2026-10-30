package com.local.diplay.clusterbridge;

public final class MusicTextPolicyTest {
    private static int checks;
    public static void main(String[] args) {
        Protocol.Song raw = Protocol.song("♪ 一句歌词 — 歌名");
        check(MusicTextPolicy.display(null,true)==null,"no source stays absent");
        check(MusicTextPolicy.display(raw,false)==raw,"default off retains original source object");
        text("一句歌词 — 歌名","一句歌词");text("一句歌词 - 歌名","一句歌词");text("一句歌词 – 歌名","一句歌词");
        text("前一句 - 后一句 — 歌名","前一句 - 后一句");
        text("前一句 — 后一句 — 歌名","前一句 — 后一句");
        text("一句歌词 — 歌名 - Live","一句歌词");
        text("word-word — 歌名","word-word");text("word-word","word-word");
        text("只有歌词，没有分隔符","只有歌词，没有分隔符");text("歌词-歌名","歌词-歌名");
        text("歌词 — ","歌词 —");text(" — 歌名","— 歌名");
        text("歌词   —   歌名","歌词");text("🎵 第一句 — 歌名","🎵 第一句");
        Protocol.Song paused = MusicTextPolicy.display(Protocol.song("❚❚ 一句歌词 — 歌名"),true);
        check(!paused.playing,"pause retained");check(MusicTextPolicy.display(raw,true).playing,"play retained");
        check("一句歌词 — 歌名".equals(raw.text),"display filtering never mutates source");
        MusicDelivery delivery = new MusicDelivery();
        delivery.sent(raw);
        Protocol.Song filtered=MusicTextPolicy.display(raw,true);
        check(delivery.next(filtered)==MusicDelivery.Action.SEND,"turning on changes current output immediately");delivery.sent(filtered);
        check(delivery.next(MusicTextPolicy.display(raw,true))==MusicDelivery.Action.NONE,"duplicate lyric suppressed");
        check(delivery.next(MusicTextPolicy.display(Protocol.song("♪ 一句歌词 — 另一歌名"),true))==MusicDelivery.Action.NONE,"suffix-only changes need no duplicate lyric");
        Protocol.Song next=MusicTextPolicy.display(Protocol.song("♪ 下一句歌词 — 歌名"),true);
        check(delivery.next(next)==MusicDelivery.Action.SEND,"new lyric forwarded");delivery.sent(next);
        check(delivery.next(MusicTextPolicy.display(Protocol.song("❚❚ 下一句歌词 — 歌名"),true))==MusicDelivery.Action.SEND,"playback changes forwarded");
        check(delivery.next(MusicTextPolicy.display(raw,false))==MusicDelivery.Action.SEND,"turning off restores complete source");
        delivery.reset();check(delivery.next(filtered)==MusicDelivery.Action.SEND,"reconnect still sends first lyric");
        System.out.println("MusicTextPolicyTest: "+checks+" checks passed");
    }
    private static void text(String input,String expected){Protocol.Song source=Protocol.song("♪ "+input);Protocol.Song output=MusicTextPolicy.display(source,true);check(expected.equals(output.text),"display mismatch: "+input);}
    private static void check(boolean okay,String message){checks++;if(!okay)throw new AssertionError(message);}
}
