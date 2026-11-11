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
    'github' => 'https://github.com/ryzhenkahvh/mps',
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
Site::start($config, $lang === 'en' ? 'en' : 'ru', $page);

header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: strict-origin-when-cross-origin');
// the site is never shown inside a page of somebody else ("preview" is only for looking at it while it is being made)
$framed = empty($config['preview']) ? " frame-ancestors 'none';" : '';
if ($framed !== '') {
    header('X-Frame-Options: DENY');
}
header("Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; form-action 'self';$framed base-uri 'self'");

$file = __DIR__ . '/pages/' . preg_replace('/[^a-z]/', '', $page) . '.php';
if (!is_file($file)) {
    http_response_code(404);
    $file = __DIR__ . '/pages/missing.php';
}
require $file;
