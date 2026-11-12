<?php
/**
 * Entry point of every page. The small index.php files in the folders of the site set
 * $page and $lang and include this file; nothing here depends on URL rewriting.
 */
declare(strict_types=1);

if (!isset($page, $lang)) {
    http_response_code(404);
    exit;
}
if (PHP_VERSION_ID < 80000) {
    http_response_code(503);
    header('Content-Type: text/plain; charset=utf-8');
    echo "Сайту нужен PHP 8.0 или новее (сейчас " . PHP_VERSION . "). Версия PHP выбирается в панели хостинга, в настройках сайта.\n";
    echo "This site needs PHP 8.0 or newer (this is " . PHP_VERSION . ").\n";
    exit;
}
define('MPS', true);
define('MPS_ROOT', dirname(__DIR__));

// After an update of the site every file is taken from the disk at once: otherwise PHP may go on using
// old copies of some files for a few seconds, and a page of the new version meets a library of the old one.
@ini_set('opcache.revalidate_freq', '0');

// visitors never see technical messages; they go to the error log of the hosting
ini_set('display_errors', '0');
set_exception_handler(static function (Throwable $e): void {
    error_log('MPS site: ' . get_class($e) . ': ' . $e->getMessage() . ' at ' . $e->getFile() . ':' . $e->getLine());
    if (!headers_sent()) {
        http_response_code(500);
        header('Content-Type: text/html; charset=utf-8');
    }
    echo '<p>Что-то пошло не так. Попробуйте ещё раз чуть позже. / Something went wrong. Please try again a little later.</p>';
});

require __DIR__ . '/lib.php';
require __DIR__ . '/store.php';

/*
 * Settings, from the weakest to the strongest:
 *  - what is written here;
 *  - what the site keeps for itself in app/data (the database, entered on the first visit to /admin/);
 *  - app/config.php, for those who prefer to set things by hand (see config.sample.php).
 */
$config = [
    'db' => null,
    'db_host' => 'localhost',
    'db_port' => 3306,
    'db_from' => '',
    'secret' => '',
    'site_url' => 'https://mp-studio.online',
    'support_email' => 'support@mp-studio.online',
    'telegram' => 'mobprodstudio',
];
$manual = is_file(__DIR__ . '/config.php') ? require __DIR__ . '/config.php' : [];
if (is_array($manual)) {
    $config = $manual + $config;
}
if (is_array($config['db']) && (string) ($config['db']['name'] ?? '') !== '') {
    $config['db_from'] = 'file';
} else {
    $kept = Keep::read('db');
    $config['db'] = is_array($kept) ? $kept : null;
    $config['db_from'] = is_array($kept) ? 'site' : '';
}
// the Telegram account: a bare name, or nothing
$config['telegram'] = ltrim(trim((string) ($config['telegram'] ?? '')), '@');
if (!preg_match('/^[A-Za-z0-9_]{4,32}$/', $config['telegram'])) {
    $config['telegram'] = '';
}
Site::start($config, $lang === 'en' ? 'en' : 'ru', $page);

/*
 * One address for the site: https://<the host of site_url>.
 * The "www." name is sent there by the server. Plain HTTP is moved to https by the page itself
 * (assets/site.js): only the browser knows for certain how it came, while a server standing
 * behind the front server of a hosting can be wrong, and a wrong guess sends visitors in circles.
 */
$home = parse_url((string) $config['site_url']);
$name = strtolower((string) ($home['host'] ?? ''));
$host = strtolower((string) preg_replace('/:\d+$/', '', (string) ($_SERVER['HTTP_HOST'] ?? '')));
if (($home['scheme'] ?? '') === 'https' && $name !== '') {
    if ($host === 'www.' . $name) {
        $method = (string) ($_SERVER['REQUEST_METHOD'] ?? 'GET');
        $path = (string) ($_SERVER['REQUEST_URI'] ?? '/');
        // 308 keeps a form a form: what was being sent is sent again to the right address
        header('Location: https://' . $name . (str_starts_with($path, '/') ? $path : '/'), true, $method === 'GET' || $method === 'HEAD' ? 301 : 308);
        exit;
    }
    $said = strtolower(trim(explode(',', (string) ($_SERVER['HTTP_X_FORWARDED_PROTO'] ?? ''))[0]));
    if ($host === $name && ($said === 'https' || ($said === '' && !empty($_SERVER['HTTPS']) && strtolower((string) $_SERVER['HTTPS']) !== 'off'))) {
        // browsers that have been here over https come straight to https for the next month
        header('Strict-Transport-Security: max-age=2592000');
    }
}

header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: strict-origin-when-cross-origin');
// the site is never shown inside a page of somebody else ("preview" is only for looking at it while it is being made)
$framed = empty($config['preview']) ? " frame-ancestors 'none';" : '';
if ($framed !== '') {
    header('X-Frame-Options: DENY');
}
header("Content-Security-Policy: default-src 'self'; img-src 'self' data:; media-src 'self'; style-src 'self'; script-src 'self'; form-action 'self';$framed base-uri 'self'");

$file = __DIR__ . '/pages/' . preg_replace('/[^a-z]/', '', $page) . '.php';
if (!is_file($file)) {
    http_response_code(404);
    $file = __DIR__ . '/pages/missing.php';
}
require $file;
