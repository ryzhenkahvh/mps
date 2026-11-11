<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}
require __DIR__ . '/../legal.php';
$mail = (string) Site::$config['support_email'];

legal_page(Site::$lang === 'en' ? [
    'title' => 'Privacy policy',
    'updated' => 'Last changed: 11 November 2026',
    'lead' => 'MPS shows no ads, sells no data and does not watch what you do in the app. We only have data about you when you create a community account, publish something or fill in a form on this site.',
    'parts' => [
        ['The app without an account', [
            'Your projects, recordings and sounds are kept on your phone only. The app sends nothing to a server until you open the Community section.',
            'When you download something from the community without an account, the server receives a random number that stands for your installation of the app. It is only there so that one download is not counted twice, and it is not tied to you or to your phone.',
        ]],
        ['A community account', [
            'When you create an account we keep:',
            [
                'your e-mail address and your password (the password in a form we cannot read);',
                'the name you chose, the few words about yourself and the colour of your badge;',
                'everything you publish: files, titles, descriptions and tags;',
                'your marks and your complaints.',
            ],
            'Your name, the words about yourself and what you publish can be seen by everybody. Your e-mail address can be seen by nobody but us; we use it to send the code that confirms the address and the code for changing a forgotten password.',
        ]],
        ['This site', [
            'Answers to the survey and messages from the feedback form are kept on our hosting. A contact (Telegram or e-mail) is left only if you wish; we use it only to answer you or to invite you to a conversation.',
            'We do not keep your IP address. To hold off spam, only an anonymous mark is kept, and it changes every day. The site uses no advertising or analytics counters; the only cookie is the one the administrator of the site signs in with.',
        ]],
        ['Where the data is kept', [
            'Community accounts and publications are kept with Supabase, on servers in the European Union (Ireland). Letters with codes are sent from our own mail server. The site, the survey answers and the feedback are kept on a hosting in Belarus.',
        ]],
        ['Who gets the data', [
            'Nobody except the services named above, which keep it on our behalf. Lawful demands of public authorities are the exception.',
        ]],
        ['How to delete your data', [
            'An account is deleted in the app: Community, your profile, "Delete account". The profile and everything you published go with it. Copies that other people have already downloaded stay with them.',
            'To have your survey answers or a message deleted, write to ' . $mail . '.',
        ]],
        ['Children', ['The community is not meant for children under 13.']],
        ['Changes', ['When this policy changes, the new text appears on this page with a new date.']],
        ['Contact', [$mail]],
    ],
] : [
    'title' => 'Политика конфиденциальности',
    'updated' => 'Последнее изменение: 11 ноября 2026 года',
    'lead' => 'MPS не показывает рекламу, не продаёт данные и не следит за тем, что вы делаете в приложении. Данные о вас появляются у нас, только когда вы создаёте аккаунт в сообществе, что-то публикуете или заполняете форму на этом сайте.',
    'parts' => [
        ['Приложение без аккаунта', [
            'Проекты, записи и звуки хранятся только на вашем телефоне. Приложение ничего не отправляет на сервер, пока вы не откроете раздел «Сообщество».',
            'Когда вы без аккаунта скачиваете что-то из сообщества, сервер получает случайный номер, обозначающий вашу установку приложения. Он нужен только затем, чтобы одно скачивание не считалось дважды, и не связан ни с вами, ни с вашим телефоном.',
        ]],
        ['Аккаунт в сообществе', [
            'Когда вы создаёте аккаунт, мы храним:',
            [
                'адрес электронной почты и пароль (пароль — в виде, который мы не можем прочитать);',
                'имя, которое вы выбрали, несколько слов о себе и цвет значка;',
                'всё, что вы публикуете: файлы, названия, описания и теги;',
                'ваши оценки и жалобы.',
            ],
            'Имя, слова о себе и публикации видны всем. Адрес почты не виден никому, кроме нас; на него приходят код подтверждения адреса и код для смены забытого пароля.',
        ]],
        ['Этот сайт', [
            'Ответы на опрос и сообщения из формы обратной связи хранятся на нашем хостинге. Контакт (Telegram или почту) вы оставляете только по желанию; мы используем его лишь для того, чтобы ответить вам или пригласить на разговор.',
            'Ваш IP-адрес мы не сохраняем. Для защиты от спама хранится только обезличенная отметка, которая меняется каждый день. На сайте нет рекламных и аналитических счётчиков; единственная cookie нужна администратору сайта для входа.',
        ]],
        ['Где хранятся данные', [
            'Аккаунты и публикации сообщества хранятся в сервисе Supabase, на серверах в Европейском союзе (Ирландия). Письма с кодами отправляются с нашего почтового сервера. Сайт, ответы на опрос и обратная связь хранятся на хостинге в Беларуси.',
        ]],
        ['Кому передаются данные', [
            'Никому, кроме перечисленных сервисов, которые хранят их по нашему поручению. Исключение — законные требования государственных органов.',
        ]],
        ['Как удалить свои данные', [
            'Аккаунт удаляется в приложении: «Сообщество» → ваш профиль → «Удалить аккаунт». Вместе с ним удаляются профиль и все публикации. Копии, которые другие люди уже скачали, остаются у них.',
            'Чтобы удалить ответы на опрос или сообщение, напишите на ' . $mail . '.',
        ]],
        ['Дети', ['Сообщество не предназначено для детей младше 13 лет.']],
        ['Изменения', ['Если политика изменится, новый текст появится на этой странице с новой датой.']],
        ['Связь', [$mail]],
    ],
]);
