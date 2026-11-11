<?php
// What the server shows for an address that does not exist (see .htaccess).

// The file of an earlier build (an update of the site replaced it): the visitor gets the current one.
$path = (string) parse_url((string) ($_SERVER['REQUEST_URI'] ?? ''), PHP_URL_PATH);
if (preg_match('#^/download/[^/]+\.apk$#i', $path)) {
    header('Cache-Control: no-store');
    header('Location: /download/get.php', true, 302);
    exit;
}

$page = 'missing';
$lang = str_starts_with((string) ($_SERVER['REQUEST_URI'] ?? ''), '/en/') ? 'en' : 'ru';
http_response_code(404);
require __DIR__ . '/app/boot.php';
