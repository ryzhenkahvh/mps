<?php
// What the server shows for an address that does not exist (see .htaccess).
$page = 'missing';
$lang = str_starts_with((string) ($_SERVER['REQUEST_URI'] ?? ''), '/en/') ? 'en' : 'ru';
http_response_code(404);
require __DIR__ . '/app/boot.php';
