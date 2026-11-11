<?php
/**
 * Settings of the site, for those who want to set them by hand. NOT needed normally:
 * the first visit to /admin/ asks for the name of the database, its user and password and
 * keeps them on the hosting in app/data/db.php; the key that signs the forms is made by the
 * site itself.
 *
 * To use this file, copy it to config.php (next to it) and keep only the lines you change.
 * config.php may hold a password: it is not part of the repository or of the site archive.
 */
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

return [
    // where the first visit to /admin/ looks for the database (the control panel of the hosting names it)
    'db_host' => 'localhost',
    'db_port' => 3306,

    // or all of it here; then /admin/ does not ask about the database at all
    // 'db' => [
    //     'host' => 'localhost',
    //     'port' => 3306,
    //     'name' => 'database_name',
    //     'user' => 'database_user',
    //     'password' => '',
    // ],

    'site_url' => 'https://mp-studio.online',
    'support_email' => 'support@mp-studio.online',
    // the Telegram account of the project, without "@"; '' hides the links to it
    'telegram' => 'mobprodstudio',
];
