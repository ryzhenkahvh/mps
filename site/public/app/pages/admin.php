<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

/**
 * The closed page of the owner: answers of the survey, feedback, download numbers.
 *
 * The first visit asks where the database is (its name, user and password from the control panel
 * of the hosting) and for a new password of this page. Being able to name a database of this
 * hosting is what shows the owner; the settings stay on the hosting in app/data/db.php.
 * After that the page asks only for its own password.
 */
header('Cache-Control: no-store');
session_name('mps_admin');
session_set_cookie_params(['lifetime' => 0, 'path' => '/admin/', 'secure' => !empty($_SERVER['HTTPS']) || ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? '') === 'https', 'httponly' => true, 'samesite' => 'Strict']);
session_start();
if (empty($_SESSION['csrf'])) {
    $_SESSION['csrf'] = bin2hex(random_bytes(16));
}
$csrf = (string) $_SESSION['csrf'];
$questions = require __DIR__ . '/../survey.php';
$kinds = ['idea' => 'Идея', 'bug' => 'Ошибка', 'praise' => 'Похвала', 'question' => 'Вопрос'];
$message = '';
$post = ($_SERVER['REQUEST_METHOD'] ?? 'GET') === 'POST';
$action = $post ? clean($_POST['action'] ?? '', 20) : '';
if ($post && !hash_equals($csrf, (string) ($_POST['csrf'] ?? ''))) {
    $action = '';
    $message = 'Форма устарела. Попробуйте ещё раз.';
}
$pdo = Store::db();
$why = Store::reason();

function admin_page(string $title, callable $content): void
{
    layout($title . ' — MPS', '', static function () use ($title, $content): void {
        echo '<section class="section first admin"><div class="wrap"><h1>' . e($title) . '</h1>';
        $content();
        echo '</div></section>';
    }, ['noindex' => true]);
}

/** What is wrong with the database, in words; the technical message could give away names. */
function db_trouble(string $why): string
{
    switch ($why) {
        case 'driver':
            return 'В PHP на хостинге не включено расширение pdo_mysql. Включите его в панели хостинга (настройки PHP сайта, список расширений) и откройте эту страницу снова.';
        case 'denied':
            return 'База не принимает имя пользователя или пароль.';
        case 'no-database':
            return 'Базы с таким именем нет или у пользователя нет к ней доступа.';
        default:
            return 'Сервер базы данных не отвечает.';
    }
}

/** Signs the owner in: the session remembers which password it was opened with. */
function admin_enter(string $hash): void
{
    session_regenerate_id(true);
    $_SESSION['admin'] = true;
    $_SESSION['admin_stamp'] = substr($hash, -16);
    header('Location: /admin/', true, 303);
    exit;
}

// ---- the site does not know its database yet
if ($why === 'not-set') {
    $name = '';
    $user = '';
    if ($action === 'install') {
        $name = clean($_POST['db_name'] ?? '', 64);
        $user = clean($_POST['db_user'] ?? '', 80);
        $new = (string) ($_POST['password'] ?? '');
        // wrong guesses are counted for everybody together: there is no database yet to count them per visitor
        $tries = array_values(array_filter((array) (Keep::read('tries')['at'] ?? []), static fn ($t) => is_int($t) && $t > time() - 3600));
        if (count($tries) >= 10) {
            $message = 'Слишком много неудачных попыток. Подождите час.';
        } elseif ($name === '' || $user === '') {
            $message = 'Укажите имя базы данных и её пользователя.';
        } elseif (strlen($new) < 10) {
            $message = 'Пароль страницы должен быть не короче 10 символов.';
        } else {
            $db = ['host' => (string) Site::$config['db_host'], 'port' => (int) Site::$config['db_port'], 'name' => $name, 'user' => $user, 'password' => (string) ($_POST['db_password'] ?? '')];
            if (!Store::open($db)) {
                $failed = Store::reason();
                if ($failed === 'denied' || $failed === 'no-database') {
                    $tries[] = time();
                    Keep::write('tries', ['at' => $tries]);
                }
                $message = db_trouble($failed);
            } elseif (!Keep::write('db', $db)) {
                $message = 'База данных ответила, но сохранить настройки не получилось: папка app/data на хостинге закрыта для записи. В менеджере файлов откройте её свойства и разрешите запись владельцу (права 755), затем отправьте форму ещё раз.';
            } else {
                Keep::forget('tries');
                $hash = password_hash($new, PASSWORD_DEFAULT);
                Store::set('admin_hash', $hash);
                admin_enter($hash);
            }
        }
    }
    admin_page('Первый вход', static function () use ($csrf, $message, $name, $user): void {
        ?>
        <?php if ($message !== ''): ?><p class="alert" role="alert"><?= e($message) ?></p><?php endif; ?>
        <p class="lead">Здесь будут ответы на опрос и отзывы. Сайту нужно знать, где их хранить: введите данные базы из панели хостинга (раздел «Базы данных») и придумайте пароль для этой страницы.</p>
        <form method="post" action="/admin/" class="form narrow-form">
          <input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="action" value="install">
          <div class="field"><label for="dbn">Имя базы данных</label><input id="dbn" type="text" name="db_name" value="<?= e($name) ?>" required maxlength="64" autocomplete="off" autocapitalize="none" spellcheck="false"></div>
          <div class="field"><label for="dbu">Пользователь базы данных</label><input id="dbu" type="text" name="db_user" value="<?= e($user) ?>" required maxlength="80" autocomplete="off" autocapitalize="none" spellcheck="false"></div>
          <div class="field"><label for="dbp">Пароль этого пользователя</label><input id="dbp" type="password" name="db_password" required autocomplete="off"></div>
          <div class="field"><label for="np">Новый пароль этой страницы (не короче 10 символов)</label><input id="np" type="password" name="password" required minlength="10" autocomplete="new-password"></div>
          <button class="button primary" type="submit">Сохранить и войти</button>
        </form>
        <p class="note">Данные базы остаются на хостинге, в файле <code>app/data/db.php</code>. В архиве сайта их нет.</p>
        <?php
    });
    return;
}

// ---- the database is known but does not answer
if (!$pdo) {
    $waiting = count(Store::waiting());
    $byHand = Site::$config['db_from'] === 'file';
    http_response_code(503);
    admin_page('Нет связи с базой данных', static function () use ($why, $waiting, $byHand): void {
        echo '<p class="alert" role="alert">' . e(db_trouble($why)) . '</p>';
        if ($why !== 'driver') {
            echo $byHand
                ? '<p>Проверьте имя базы, пользователя и пароль в файле <code>app/config.php</code> на хостинге.</p>'
                : '<p>Если вы меняли пароль базы в панели хостинга, удалите в менеджере файлов файл <code>app/data/db.php</code> и откройте эту страницу снова: сайт заново спросит данные базы.</p>';
        }
        echo '<p>Ответы посетителей не теряются: пока база недоступна, они складываются в запасной файл и попадут в базу, как только связь появится. Сейчас в запасном файле: ' . $waiting . '.</p>';
    });
    return;
}

$hash = Store::setting('admin_hash');
$logged = !empty($_SESSION['admin']) && $hash !== null && hash_equals((string) ($_SESSION['admin_stamp'] ?? ''), substr($hash, -16));

if ($action === 'setup' && $hash === null) {
    $new = (string) ($_POST['password'] ?? '');
    if (Store::recent('login_fail', Site::visitor()) >= 8) {
        $message = 'Слишком много попыток. Подождите час.';
    } elseif (!hash_equals((string) (Site::$config['db']['password'] ?? ''), (string) ($_POST['db_password'] ?? ''))) {
        Store::event('login_fail', 'setup');
        $message = 'Пароль базы данных не подошёл.';
    } elseif (strlen($new) < 10) {
        $message = 'Пароль страницы должен быть не короче 10 символов.';
    } else {
        $hash = password_hash($new, PASSWORD_DEFAULT);
        Store::set('admin_hash', $hash);
        admin_enter($hash);
    }
} elseif ($action === 'login' && $hash !== null) {
    if (Store::recent('login_fail', Site::visitor()) >= 8) {
        $message = 'Слишком много попыток. Подождите час.';
    } elseif (password_verify((string) ($_POST['password'] ?? ''), $hash)) {
        admin_enter($hash);
    } else {
        Store::event('login_fail', 'login');
        $message = 'Пароль не подошёл.';
    }
} elseif ($action === 'logout') {
    $_SESSION = [];
    session_destroy();
    header('Location: /admin/', true, 303);
    exit;
} elseif ($action === 'delete' && $logged) {
    $pdo->prepare('DELETE FROM mps_responses WHERE id = ?')->execute([(int) ($_POST['id'] ?? 0)]);
    header('Location: /admin/?tab=' . rawurlencode(clean($_POST['tab'] ?? 'survey', 20)), true, 303);
    exit;
} elseif ($action === 'password' && $logged) {
    $new = (string) ($_POST['password'] ?? '');
    if (!password_verify((string) ($_POST['old'] ?? ''), (string) $hash)) {
        $message = 'Прежний пароль не подошёл.';
    } elseif (strlen($new) < 10) {
        $message = 'Новый пароль должен быть не короче 10 символов.';
    } else {
        $hash = password_hash($new, PASSWORD_DEFAULT);
        Store::set('admin_hash', $hash);
        $_SESSION['admin_stamp'] = substr($hash, -16);
        $message = 'Пароль изменён.';
    }
}

// ---- the database is set by hand in app/config.php and the page has no password yet
if ($hash === null) {
    admin_page('Первый вход', static function () use ($csrf, $message): void {
        ?>
        <?php if ($message !== ''): ?><p class="alert" role="alert"><?= e($message) ?></p><?php endif; ?>
        <p class="lead">Здесь будут ответы на опрос и отзывы. Придумайте пароль для этой страницы и подтвердите, что вы владелец сайта: введите пароль базы данных.</p>
        <form method="post" action="/admin/" class="form narrow-form">
          <input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="action" value="setup">
          <div class="field"><label for="dbp">Пароль базы данных</label><input id="dbp" type="password" name="db_password" required autocomplete="off"></div>
          <div class="field"><label for="np">Новый пароль этой страницы (не короче 10 символов)</label><input id="np" type="password" name="password" required minlength="10" autocomplete="new-password"></div>
          <button class="button primary" type="submit">Сохранить и войти</button>
        </form>
        <?php
    });
    return;
}
if (!$logged) {
    $byHand = Site::$config['db_from'] === 'file';
    admin_page('Вход', static function () use ($csrf, $message, $byHand): void {
        ?>
        <?php if ($message !== ''): ?><p class="alert" role="alert"><?= e($message) ?></p><?php endif; ?>
        <form method="post" action="/admin/" class="form narrow-form">
          <input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="action" value="login">
          <div class="field"><label for="p">Пароль</label><input id="p" type="password" name="password" required autocomplete="current-password"></div>
          <button class="button primary" type="submit">Войти</button>
        </form>
        <?php if ($byHand): ?>
          <p class="note">Забыли пароль? Удалите в базе данных строку <code>admin_hash</code> из таблицы <code>mps_settings</code> (через phpMyAdmin в панели хостинга) и откройте эту страницу снова.</p>
        <?php else: ?>
          <p class="note">Забыли пароль? Удалите в менеджере файлов хостинга файл <code>app/data/db.php</code> и откройте эту страницу снова: сайт спросит данные базы и новый пароль. Ответы при этом останутся на месте.</p>
        <?php endif; ?>
        <?php
    });
    return;
}

/** An answer in words: the labels of the options instead of their keys, then what the person added. */
function answer_text(array $q, array $answers): string
{
    $value = $answers[$q['id']] ?? null;
    $parts = [];
    if ($q['type'] === 'one' || $q['type'] === 'many') {
        foreach ((array) $value as $v) {
            if (is_scalar($v)) {
                $parts[] = (string) ($q['options'][(string) $v]['ru'] ?? $v);
            }
        }
    } elseif (is_scalar($value) && (string) $value !== '') {
        $parts[] = (string) $value;
    }
    $other = $answers[$q['id'] . '_other'] ?? '';
    if (is_string($other) && $other !== '') {
        $parts[] = '«' . $other . '»';
    }
    return implode(', ', $parts);
}

// ---- a table for a spreadsheet
$export = clean($_GET['export'] ?? '', 20);
if ($export === 'survey' || $export === 'feedback') {
    header('Content-Type: text/csv; charset=utf-8');
    header('Content-Disposition: attachment; filename="mps-' . $export . '-' . gmdate('Y-m-d') . '.csv"');
    $out = fopen('php://output', 'w');
    fwrite($out, "\xEF\xBB\xBF");
    $head = ['№', 'Дата (UTC)', 'Язык', 'Откуда'];
    if ($export === 'survey') {
        foreach ($questions as $q) {
            $head[] = $q['q']['ru'];
        }
    } else {
        array_push($head, 'Версия', 'Устройство', 'О чём', 'Сообщение');
    }
    $head[] = 'Контакт';
    fputcsv($out, $head, ';', '"', '');
    $st = $pdo->prepare('SELECT * FROM mps_responses WHERE kind = ? ORDER BY id');
    $st->execute([$export]);
    foreach ($st as $row) {
        $a = json_decode((string) $row['answers'], true) ?: [];
        $line = [$row['id'], $row['created_at'], $row['lang'], $row['source']];
        if ($export === 'survey') {
            foreach ($questions as $q) {
                $line[] = answer_text($q, $a);
            }
        } else {
            array_push($line, $row['app_version'], $row['device'], $kinds[$a['kind'] ?? ''] ?? '', $a['text'] ?? '');
        }
        $line[] = $row['contact'];
        // a cell that starts like a formula is kept as text by a leading apostrophe
        fputcsv($out, array_map(static fn ($c) => preg_match('/^[=+\-@]/', (string) $c) ? "'" . $c : (string) $c, $line), ';', '"', '');
    }
    return;
}

$tab = in_array($_GET['tab'] ?? '', ['stats', 'survey', 'feedback', 'settings'], true) ? $_GET['tab'] : 'summary';
$count = static function (string $sql, array $args = []) use ($pdo): int {
    $st = $pdo->prepare($sql);
    $st->execute($args);
    return (int) $st->fetchColumn();
};
$totals = [
    'survey' => $count('SELECT COUNT(*) FROM mps_responses WHERE kind = \'survey\''),
    'survey7' => $count('SELECT COUNT(*) FROM mps_responses WHERE kind = \'survey\' AND created_at > UTC_TIMESTAMP() - INTERVAL 7 DAY'),
    'feedback' => $count('SELECT COUNT(*) FROM mps_responses WHERE kind = \'feedback\''),
    'feedback7' => $count('SELECT COUNT(*) FROM mps_responses WHERE kind = \'feedback\' AND created_at > UTC_TIMESTAMP() - INTERVAL 7 DAY'),
    'downloads' => $count('SELECT COUNT(*) FROM mps_events WHERE kind = \'download\''),
    'downloads7' => $count('SELECT COUNT(*) FROM mps_events WHERE kind = \'download\' AND created_at > UTC_TIMESTAMP() - INTERVAL 7 DAY'),
    'contacts' => $count('SELECT COUNT(*) FROM mps_responses WHERE kind = \'survey\' AND contact <> \'\''),
];

admin_page('Ответы и отзывы', static function () use ($pdo, $questions, $kinds, $tab, $totals, $csrf, $message): void {
    ?>
    <?php if ($message !== ''): ?><p class="alert" role="status"><?= e($message) ?></p><?php endif; ?>
    <ul class="stats">
      <li><b><?= $totals['survey'] ?></b><span>анкет<br><small>за 7 дней: <?= $totals['survey7'] ?></small></span></li>
      <li><b><?= $totals['contacts'] ?></b><span>готовы к разговору</span></li>
      <li><b><?= $totals['feedback'] ?></b><span>отзывов<br><small>за 7 дней: <?= $totals['feedback7'] ?></small></span></li>
      <li><b><?= $totals['downloads'] ?></b><span>скачиваний<br><small>за 7 дней: <?= $totals['downloads7'] ?></small></span></li>
    </ul>
    <nav class="tabs" aria-label="Разделы">
      <a href="/admin/"<?= $tab === 'summary' ? ' aria-current="page"' : '' ?>>Сводка</a>
      <a href="/admin/?tab=stats"<?= $tab === 'stats' ? ' aria-current="page"' : '' ?>>Статистика</a>
      <a href="/admin/?tab=survey"<?= $tab === 'survey' ? ' aria-current="page"' : '' ?>>Анкеты</a>
      <a href="/admin/?tab=feedback"<?= $tab === 'feedback' ? ' aria-current="page"' : '' ?>>Отзывы</a>
      <a href="/admin/?tab=settings"<?= $tab === 'settings' ? ' aria-current="page"' : '' ?>>Настройки</a>
    </nav>
    <?php
    if ($tab === 'stats') {
        // charts: another view of the same rows, nothing is written
        require_once __DIR__ . '/../qr.php';
        require_once __DIR__ . '/../charts.php';
        $days = (int) ($_GET['days'] ?? 30);
        $publicUrl = rtrim((string) Site::$config['site_url'], '/') . '/stats/';
        echo '<div class="stats-deck" data-slide-deck data-theme="dark" data-slide-qr="0" data-brand="MPS · mp-studio.online">'
            . stats_view($pdo, in_array($days, [7, 30, 90, 365], true) ? $days : 30, $kinds)
            . '</div>';
        return;
    }
    if ($tab === 'summary') {
        require_once __DIR__ . '/../qr.php';
        require_once __DIR__ . '/../charts.php';
        $rows = $pdo->query('SELECT answers, source, lang, created_at FROM mps_responses WHERE kind = \'survey\' ORDER BY id')->fetchAll();
        $n = count($rows);
        if ($n === 0) {
            echo '<p class="lead">Анкет пока нет. Ссылка на опрос: <code>' . e(rtrim((string) Site::$config['site_url'], '/')) . '/survey/</code>. Чтобы видеть, откуда пришли люди, добавляйте к ссылке метку, например <code>/survey/?src=telegram</code>.</p>';
            return;
        }
        $sources = [];
        $own = [];
        foreach ($rows as $row) {
            $a = json_decode((string) $row['answers'], true) ?: [];
            $sources[$row['source']] = ($sources[$row['source']] ?? 0) + 1;
            foreach ($questions as $q) {
                $id = $q['id'];
                if (is_string($a[$id . '_other'] ?? null) && trim($a[$id . '_other']) !== '') {
                    $own[$id][] = trim($a[$id . '_other']);
                }
                if ($q['type'] === 'text' && is_string($a[$id] ?? null) && trim($a[$id]) !== '') {
                    $own[$id][] = trim($a[$id]);
                }
            }
        }
        arsort($sources);
        $publicUrl = rtrim((string) Site::$config['site_url'], '/') . '/stats/';
        echo '<p class="note">Откуда пришли: ' . e(implode(', ', array_map(static fn ($k, $v) => $k . ' — ' . $v, array_keys($sources), $sources)))
            . ' · <a href="/admin/?export=survey">Скачать таблицу (CSV)</a>'
            . ' · <a href="' . e(Site::url('stats', 'ru')) . '">Публичная страница графиков (/stats/)</a></p>';

        echo survey_presentation_stats($rows, $questions, 'ru', [
            'baseUrl' => '/admin/?tab=summary',
            'segment' => clean($_GET['segment'] ?? 'all', 20),
            'qrUrl' => $publicUrl,
            'downloads' => $totals['downloads'],
        ]);

        // Owner-only free-text quotes and "other" entries (never shown on public /stats/)
        $hasOwn = array_filter($own);
        if ($hasOwn) {
            echo '<h2>Ответы своими словами (только в админке)</h2><div class="charts small">';
            foreach ($questions as $q) {
                if (empty($own[$q['id']])) {
                    continue;
                }
                $items = array_slice(array_reverse($own[$q['id']]), 0, 30);
                echo '<section class="card"><h3>' . e($q['q']['ru']) . '</h3><p class="note">' . e(implode(' · ', $items)) . '</p></section>';
            }
            echo '</div>';
        }
        return;
    }
    if ($tab === 'settings') {
        ?>
        <h2>Пароль этой страницы</h2>
        <form method="post" action="/admin/?tab=settings" class="form narrow-form">
          <input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="action" value="password">
          <div class="field"><label for="old">Прежний пароль</label><input id="old" type="password" name="old" required autocomplete="current-password"></div>
          <div class="field"><label for="new">Новый пароль (не короче 10 символов)</label><input id="new" type="password" name="password" required minlength="10" autocomplete="new-password"></div>
          <button class="button primary" type="submit">Сменить пароль</button>
        </form>
        <form method="post" action="/admin/" class="form"><input type="hidden" name="csrf" value="<?= e($csrf) ?>"><input type="hidden" name="action" value="logout"><button class="button ghost" type="submit">Выйти</button></form>
        <?php
        return;
    }
    $per = 30;
    $page = max(1, (int) ($_GET['p'] ?? 1));
    $st = $pdo->prepare('SELECT * FROM mps_responses WHERE kind = ? ORDER BY id DESC LIMIT ' . $per . ' OFFSET ' . (($page - 1) * $per));
    $st->execute([$tab]);
    $rows = $st->fetchAll();
    $total = $totals[$tab];
    echo '<p class="note">Всего: ' . $total . '. <a href="/admin/?export=' . e($tab) . '">Скачать таблицу (CSV)</a></p>';
    if (!$rows) {
        echo '<p class="lead">Пока пусто.</p>';
    }
    foreach ($rows as $row) {
        $a = json_decode((string) $row['answers'], true) ?: [];
        echo '<article class="answer"><header><b>№ ' . (int) $row['id'] . '</b> · ' . e($row['created_at']) . ' UTC · ' . e($row['lang']) . ' · ' . e($row['source']);
        if ($tab === 'feedback') {
            echo ' · ' . e($kinds[$a['kind'] ?? ''] ?? '') . ($row['app_version'] !== '' ? ' · версия ' . e($row['app_version']) : '') . ($row['device'] !== '' ? ' · ' . e($row['device']) : '');
        }
        echo '</header>';
        if ($tab === 'feedback') {
            echo '<p class="said">' . nl2br(e($a['text'] ?? '')) . '</p>';
        } else {
            echo '<dl>';
            foreach ($questions as $q) {
                $text = answer_text($q, $a);
                if ($text !== '') {
                    echo '<div><dt>' . e($q['q']['ru']) . '</dt><dd>' . nl2br(e($text)) . '</dd></div>';
                }
            }
            echo '</dl>';
        }
        if ($row['contact'] !== '') {
            echo '<p class="contact">Контакт: <b>' . e($row['contact']) . '</b></p>';
        }
        echo '<form method="post" action="/admin/"><input type="hidden" name="csrf" value="' . e($csrf) . '"><input type="hidden" name="action" value="delete"><input type="hidden" name="tab" value="' . e($tab) . '"><input type="hidden" name="id" value="' . (int) $row['id'] . '"><button type="submit" class="link danger" data-confirm="Удалить эту запись?">Удалить</button></form></article>';
    }
    if ($total > $per) {
        echo '<nav class="pager" aria-label="Страницы">';
        for ($i = 1; $i <= (int) ceil($total / $per); $i++) {
            echo '<a href="/admin/?tab=' . e($tab) . '&amp;p=' . $i . '"' . ($i === $page ? ' aria-current="page"' : '') . '>' . $i . '</a>';
        }
        echo '</nav>';
    }
});
