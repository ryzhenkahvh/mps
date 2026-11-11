<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}
require __DIR__ . '/../legal.php';
$mail = (string) Site::$config['support_email'];

legal_page(Site::$lang === 'en' ? [
    'title' => 'Community rules',
    'updated' => 'Last changed: 11 November 2026',
    'lead' => 'The MPS community is where people who use the app share sounds, presets, projects and tracks. By using it you accept these rules.',
    'parts' => [
        ['What may be published', [
            'Only what you made yourself or have the right to share.',
            'Not allowed:',
            [
                'other people\'s tracks and samples without permission of the rights holder;',
                'anything that breaks the law;',
                'insults, threats and harassment;',
                'spam and files meant to do harm.',
            ],
        ]],
        ['What happens to what you publish', [
            'By publishing something you allow other users to download it for free and to use it in their own music, commercial music included. You remain its author.',
            'You can remove a publication at any time. Copies that have been downloaded already stay with those who downloaded them.',
        ]],
        ['Complaints and moderation', [
            'Anyone signed in can complain about a publication. After several complaints it is hidden until it has been looked at. Moderators may remove publications and accounts that break these rules.',
            'If your rights are infringed, write to ' . $mail . ': name the publication and say what your rights are based on.',
        ]],
        ['Your account', [
            'One account per person. Do not give your password to anyone. You can delete your account in the app; your publications are removed with it.',
        ]],
        ['Limits', [
            'A file may be up to 10 MB, one author may keep up to 50 MB on the server, and the number of publications per day is limited.',
            'The community is provided as it is. We do our best to keep it running, but we do not promise that it is always available and we are not liable for the loss of what was published. Keep copies of your own.',
        ]],
        ['Changes', ['When these rules change, the new text appears on this page with a new date.']],
        ['Contact', [$mail]],
    ],
] : [
    'title' => 'Правила сообщества',
    'updated' => 'Последнее изменение: 11 ноября 2026 года',
    'lead' => 'Сообщество MPS — место, где пользователи приложения делятся звуками, пресетами, проектами и треками. Пользуясь им, вы принимаете эти правила.',
    'parts' => [
        ['Что можно публиковать', [
            'Только то, что вы сделали сами или чем вправе делиться.',
            'Нельзя публиковать:',
            [
                'чужие треки и сэмплы без разрешения правообладателя;',
                'всё, что нарушает закон;',
                'оскорбления, угрозы и травлю;',
                'спам и файлы, созданные, чтобы навредить.',
            ],
        ]],
        ['Что происходит с опубликованным', [
            'Публикуя материал, вы разрешаете другим пользователям бесплатно скачивать его и использовать в собственной музыке, в том числе коммерческой. Авторство остаётся за вами.',
            'Публикацию можно удалить в любой момент. Копии, которые уже скачаны, остаются у тех, кто их скачал.',
        ]],
        ['Жалобы и модерация', [
            'Любой вошедший пользователь может пожаловаться на публикацию. После нескольких жалоб она скрывается до проверки. Модераторы вправе удалять публикации и аккаунты, нарушающие эти правила.',
            'Если нарушены ваши права, напишите на ' . $mail . ': назовите публикацию и объясните, на чём основаны ваши права.',
        ]],
        ['Аккаунт', [
            'Один человек — один аккаунт. Никому не сообщайте пароль. Аккаунт можно удалить в приложении; вместе с ним удаляются ваши публикации.',
        ]],
        ['Ограничения', [
            'Файл может быть не больше 10 МБ, один автор может хранить на сервере до 50 МБ, число публикаций в день ограничено.',
            'Сообщество предоставляется как есть. Мы стараемся, чтобы оно работало, но не обещаем, что оно доступно всегда, и не отвечаем за утрату опубликованного. Храните копии у себя.',
        ]],
        ['Изменения', ['Если правила изменятся, новый текст появится на этой странице с новой датой.']],
        ['Связь', [$mail]],
    ],
]);
