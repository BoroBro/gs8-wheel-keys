package com.gs8.wheelkeys;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Таблица соответствия: строка из лога ZHLogic -> действие в HUR.
 *
 * Как это работает:
 *  - Штатная служба магнитолы com.zhonghong.logic на каждое нажатие кнопки руля
 *    пишет в лог строку вида "CanKeyHelper: translatePanelKey KEYCODE_NEXT".
 *  - Когда играет Android Auto (HUR), медиакнопки она отбрасывает (баг прошивки),
 *    но в лог их всё равно пишет.
 *  - Мы ловим эти строки и сами отправляем медиакоманду в HUR.
 *
 * Что отдаём в HUR — это стандартные медиа-коды KeyEvent:
 *  87 = KEYCODE_MEDIA_NEXT, 88 = KEYCODE_MEDIA_PREVIOUS,
 *  85 = KEYCODE_MEDIA_PLAY_PAUSE, 86 = KEYCODE_MEDIA_STOP.
 *
 * ЧТОБЫ ИЗМЕНИТЬ РАСКЛАДКУ — правьте список MAP ниже.
 * ACTION_NONE означает "не трогаем, оставляем штатное поведение".
 */
public class KeyMap {

    public static final int ACTION_NONE = 0;

    public static class Rule {
        public final String logMatch;   // подстрока, которую ищем в строке лога
        public final int mediaKeyCode;  // какой медиа-код слать в HUR (или ACTION_NONE)
        public final String label;      // человекочитаемое описание (для экрана/лога)

        Rule(String logMatch, int mediaKeyCode, String label) {
            this.logMatch = logMatch;
            this.mediaKeyCode = mediaKeyCode;
            this.label = label;
        }
    }

    /**
     * Порядок важен: проверяется сверху вниз, срабатывает первое совпадение.
     * Коды кнопок руля GS8 (подтверждены живым логом 2026-11-24):
     *   короткий Next  -> KEYCODE_NEXT
     *   короткий Prev  -> KEYCODE_PREV
     *   короткий голос -> KEYCODE_iFLY / KEYCODE_VOICE   (оставляем ассистенту)
     *   ДОЛГИЙ голос   -> KEYCODE_EASYCONN               (свободен -> на Play/Pause)
     *   короткая трубка-> KEYCODE_PICK_UP                (оставляем телефону)
     *   ДОЛГАЯ трубка  -> KEYCODE_HANG_UP                (оставляем телефону)
     *   громкость +/-  -> VOL_INC / VOL_DEC              (работает штатно, не трогаем)
     */
    public static List<Rule> rules() {
        List<Rule> m = new ArrayList<>();

        // --- то, что чиним: переключение треков и пауза в Android Auto ---
        m.add(new Rule("translatePanelKey KEYCODE_NEXT",
                KeyEvent.KEYCODE_MEDIA_NEXT, "Руль Next -> HUR следующий трек"));
        m.add(new Rule("translatePanelKey KEYCODE_PREV",
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Руль Prev -> HUR предыдущий трек"));

        // --- бонус: длинное нажатие голоса (EasyConnect) ничем не занято -> Play/Pause ---
        m.add(new Rule("translatePanelKey KEYCODE_EASYCONN",
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, "Руль долгий голос -> HUR пауза/воспр."));

        // --- остальное оставляем штатным (примеры, по умолчанию выключены) ---
        // Короткий голос — пусть работает штатный ассистент:
        // m.add(new Rule("KEYCODE_iFLY or KEYCODE_VOICE", ACTION_NONE, "Голос (штатный)"));
        // Трубка — пусть работает телефон:
        // m.add(new Rule("translatePanelKey KEYCODE_PICK_UP", ACTION_NONE, "Трубка (штатная)"));
        // m.add(new Rule("translatePanelKey KEYCODE_HANG_UP", ACTION_NONE, "Отбой (штатный)"));

        return m;
    }
}
