<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

/**
 * The questions of the survey. The same list draws the form, checks what was sent and labels
 * the answers on the results page, so a question is changed in one place only.
 *
 * type: one (pick one), many (pick several, "max" limits how many), text, scale (0..10)
 * other: the question also takes a line of free text (saved as <id>_other)
 * when: shown and saved only if another question got that answer
 */
return [
    [
        'id' => 'role', 'type' => 'one', 'required' => true,
        'q' => ['ru' => 'Что у вас с музыкой сейчас?', 'en' => 'Where are you with music right now?'],
        'options' => [
            'listen' => ['ru' => 'Пока только слушаю, хочу попробовать', 'en' => 'I only listen so far and want to try'],
            'start' => ['ru' => 'Недавно начал', 'en' => 'I have started recently'],
            'self' => ['ru' => 'Делаю для себя', 'en' => 'I make music for myself'],
            'release' => ['ru' => 'Выкладываю треки', 'en' => 'I release tracks'],
            'pro' => ['ru' => 'Зарабатываю музыкой', 'en' => 'I earn money with music'],
        ],
    ],
    [
        'id' => 'genres', 'type' => 'many', 'other' => true,
        'q' => ['ru' => 'Что вы делаете или хотите делать?', 'en' => 'What do you make, or want to make?'],
        'options' => [
            'hiphop' => ['ru' => 'Хип-хоп, трэп', 'en' => 'Hip-hop, trap'],
            'electronic' => ['ru' => 'Электроника: хаус, техно, драм-н-бейс', 'en' => 'Electronic: house, techno, drum and bass'],
            'pop' => ['ru' => 'Поп, песни', 'en' => 'Pop, songs'],
            'lofi' => ['ru' => 'Lo-fi, эмбиент', 'en' => 'Lo-fi, ambient'],
            'rock' => ['ru' => 'Рок, живые инструменты', 'en' => 'Rock, live instruments'],
        ],
    ],
    [
        'id' => 'tools', 'type' => 'many', 'required' => true, 'other' => true,
        'q' => ['ru' => 'Чем вы пользуетесь, чтобы делать музыку?', 'en' => 'What do you use to make music?'],
        'options' => [
            'fl_pc' => ['ru' => 'FL Studio на компьютере', 'en' => 'FL Studio on a computer'],
            'daw_pc' => ['ru' => 'Ableton, Logic или другая программа на компьютере', 'en' => 'Ableton, Logic or another program on a computer'],
            'fl_mobile' => ['ru' => 'FL Studio Mobile', 'en' => 'FL Studio Mobile'],
            'bandlab' => ['ru' => 'BandLab', 'en' => 'BandLab'],
            'garageband' => ['ru' => 'GarageBand', 'en' => 'GarageBand'],
            'pads' => ['ru' => 'Groovepad, Drum Pad Machine и похожие', 'en' => 'Groovepad, Drum Pad Machine and the like'],
            'other_mobile' => ['ru' => 'Другое приложение на телефоне', 'en' => 'Another app on the phone'],
            'none' => ['ru' => 'Пока ничем', 'en' => 'Nothing yet'],
        ],
    ],
    [
        'id' => 'where', 'type' => 'one',
        'q' => ['ru' => 'Где вы делаете музыку чаще всего?', 'en' => 'Where do you make music most often?'],
        'options' => [
            'phone' => ['ru' => 'На телефоне', 'en' => 'On the phone'],
            'tablet' => ['ru' => 'На планшете', 'en' => 'On a tablet'],
            'pc' => ['ru' => 'На компьютере', 'en' => 'On a computer'],
            'mix' => ['ru' => 'Начинаю на телефоне, заканчиваю на компьютере', 'en' => 'I start on the phone and finish on a computer'],
        ],
    ],
    [
        'id' => 'freq', 'type' => 'one',
        'q' => ['ru' => 'Как часто?', 'en' => 'How often?'],
        'options' => [
            'daily' => ['ru' => 'Почти каждый день', 'en' => 'Almost every day'],
            'weekly' => ['ru' => 'Пару раз в неделю', 'en' => 'A couple of times a week'],
            'monthly' => ['ru' => 'Пару раз в месяц', 'en' => 'A couple of times a month'],
            'rarely' => ['ru' => 'Реже', 'en' => 'Less often'],
        ],
    ],
    [
        'id' => 'lost', 'type' => 'one',
        'q' => ['ru' => 'Как часто вы теряли музыкальную идею, потому что были не за своей DAW — программой, в которой делаете музыку?', 'en' => 'How often have you lost a musical idea because you were away from your DAW, the program you make music in?'],
        'options' => [
            'often' => ['ru' => 'Часто', 'en' => 'Often'],
            'sometimes' => ['ru' => 'Иногда', 'en' => 'Sometimes'],
            'rarely' => ['ru' => 'Редко', 'en' => 'Rarely'],
            'never' => ['ru' => 'Никогда', 'en' => 'Never'],
        ],
    ],
    [
        'id' => 'last', 'type' => 'text',
        'q' => ['ru' => 'Вспомните последний раз, когда вы делали музыку на телефоне. Что хотели сделать и на чём остановились?', 'en' => 'Think of the last time you made music on your phone. What did you want to do, and where did you stop?'],
        'hint' => ['ru' => 'Например: хотел набросать бит в автобусе, но не нашёл нормальный бас и бросил.', 'en' => 'For example: I wanted to sketch a beat on the bus, could not find a decent bass and gave up.'],
    ],
    [
        'id' => 'pains', 'type' => 'many', 'max' => 3, 'required' => true, 'other' => true,
        'q' => ['ru' => 'Что мешает сильнее всего, когда делаете музыку на телефоне?', 'en' => 'What gets in the way most when you make music on a phone?'],
        'options' => [
            'screen' => ['ru' => 'Маленький экран, трудно попадать', 'en' => 'The small screen, it is hard to hit things'],
            'latency' => ['ru' => 'Звук запаздывает, когда играю или записываю', 'en' => 'The sound lags when I play or record'],
            'sounds' => ['ru' => 'Мало хороших звуков и инструментов', 'en' => 'Too few good sounds and instruments'],
            'learning' => ['ru' => 'Сложно разобраться', 'en' => 'It is hard to figure out'],
            'crashes' => ['ru' => 'Приложение тормозит или вылетает', 'en' => 'The app is slow or crashes'],
            'transfer' => ['ru' => 'Трудно перенести проект на компьютер', 'en' => 'Moving a project to a computer is hard'],
            'money' => ['ru' => 'Реклама, подписки, всё платное', 'en' => 'Ads, subscriptions, everything costs money'],
            'alone' => ['ru' => 'Некому показать, нет отклика', 'en' => 'Nobody to show it to, no response'],
            'nothing' => ['ru' => 'Не делаю музыку на телефоне', 'en' => 'I do not make music on a phone'],
        ],
    ],
    [
        'id' => 'sounds', 'type' => 'many', 'other' => true,
        'q' => ['ru' => 'Откуда вы берёте звуки и сэмплы?', 'en' => 'Where do your sounds and samples come from?'],
        'options' => [
            'builtin' => ['ru' => 'Встроенные в программу', 'en' => 'The ones built into the program'],
            'free' => ['ru' => 'Бесплатные наборы из интернета', 'en' => 'Free packs from the internet'],
            'paid' => ['ru' => 'Splice и другие платные сервисы', 'en' => 'Splice and other paid services'],
            'record' => ['ru' => 'Записываю сам', 'en' => 'I record them myself'],
            'cut' => ['ru' => 'Нарезаю из готовых треков', 'en' => 'I cut them out of finished tracks'],
            'none' => ['ru' => 'Не использую', 'en' => 'I do not use any'],
        ],
    ],
    [
        'id' => 'share', 'type' => 'many',
        'q' => ['ru' => 'Куда попадает готовая музыка?', 'en' => 'Where does your finished music go?'],
        'options' => [
            'nowhere' => ['ru' => 'Никуда, остаётся у меня', 'en' => 'Nowhere, it stays with me'],
            'friends' => ['ru' => 'Друзьям в мессенджеры', 'en' => 'To friends in messengers'],
            'short' => ['ru' => 'TikTok, Reels, Shorts', 'en' => 'TikTok, Reels, Shorts'],
            'soundcloud' => ['ru' => 'SoundCloud, BandLab, Bandcamp', 'en' => 'SoundCloud, BandLab, Bandcamp'],
            'streaming' => ['ru' => 'Spotify, Яндекс Музыка, VK Музыка', 'en' => 'Spotify, Apple Music and other streaming'],
            'youtube' => ['ru' => 'YouTube', 'en' => 'YouTube'],
        ],
    ],
    [
        'id' => 'paid', 'type' => 'one',
        'q' => ['ru' => 'Сколько вы потратили на музыкальные программы и звуки за последний год?', 'en' => 'How much have you spent on music software and sounds in the last year?'],
        'options' => [
            'zero' => ['ru' => 'Ничего', 'en' => 'Nothing'],
            'low' => ['ru' => 'До 10 долларов', 'en' => 'Up to 10 dollars'],
            'mid' => ['ru' => 'От 10 до 50 долларов', 'en' => '10 to 50 dollars'],
            'high' => ['ru' => 'Больше 50 долларов', 'en' => 'More than 50 dollars'],
        ],
    ],
    [
        'id' => 'tried', 'type' => 'one', 'required' => true,
        'q' => ['ru' => 'Вы уже пробовали MPS?', 'en' => 'Have you tried MPS yet?'],
        'options' => [
            'yes' => ['ru' => 'Да', 'en' => 'Yes'],
            'no' => ['ru' => 'Ещё нет', 'en' => 'Not yet'],
        ],
    ],
    [
        'id' => 'liked', 'type' => 'text', 'when' => ['tried' => 'yes'],
        'q' => ['ru' => 'Что в MPS понравилось?', 'en' => 'What did you like in MPS?'],
    ],
    [
        'id' => 'missing', 'type' => 'text', 'when' => ['tried' => 'yes'],
        'q' => ['ru' => 'Чего не хватило или что мешало?', 'en' => 'What was missing, or what got in the way?'],
    ],
    [
        'id' => 'nps', 'type' => 'scale', 'when' => ['tried' => 'yes'],
        'q' => ['ru' => 'Насколько вероятно, что вы посоветуете MPS другу?', 'en' => 'How likely are you to recommend MPS to a friend?'],
        'low' => ['ru' => 'точно нет', 'en' => 'not at all'],
        'high' => ['ru' => 'обязательно', 'en' => 'certainly'],
    ],
    [
        'id' => 'wish', 'type' => 'text',
        'q' => ['ru' => 'Если бы в приложении для музыки можно было исправить или добавить одну вещь, что бы это было?', 'en' => 'If you could fix or add one thing in a music app, what would it be?'],
    ],
];
