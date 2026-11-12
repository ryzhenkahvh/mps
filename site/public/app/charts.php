<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

/**
 * Charts of the closed page. They are plain SVG made on the server: the site allows neither inline
 * styles nor foreign scripts, and a chart needs neither. Colours come from classes in site.css.
 *
 * Nothing here writes to the database: the charts are another view of the rows the site has kept
 * all along (mps_events, mps_responses), so they show everything collected before they existed.
 */

const CHART_MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
const CHART_MONTHS_SHORT = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];

/** The step between grid lines: 1, 2 or 5 times a power of ten, so that a chart gets at most four of them. */
function chart_step(int $max): int
{
    $step = 1;
    foreach ([1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100000, 200000, 500000] as $s) {
        $step = $s;
        if ($max <= $s * 4) {
            break;
        }
    }
    return $step;
}

function chart_num(float $v): string
{
    return rtrim(rtrim(number_format($v, 1, '.', ''), '0'), '.');
}

/** "1 анкета", "3 анкеты", "5 анкет". */
function chart_plural(int $n, string $one, string $few, string $many): string
{
    $n10 = $n % 10;
    $n100 = $n % 100;
    if ($n10 === 1 && $n100 !== 11) {
        return $one;
    }
    return $n10 >= 2 && $n10 <= 4 && ($n100 < 12 || $n100 > 14) ? $few : $many;
}

/** The grid lines and the numbers at the left edge that bars and lines share. */
function chart_grid(int $top, int $step, float $left, float $right, float $y0, float $height): string
{
    $out = '';
    for ($v = 0; $v <= $top; $v += $step) {
        $y = chart_num($y0 + $height - $height * $v / $top);
        $out .= '<line class="grid" x1="' . chart_num($left) . '" x2="' . chart_num($right) . '" y1="' . $y . '" y2="' . $y . '"/>'
            . '<text class="tick" x="' . chart_num($left - 6) . '" y="' . chart_num((float) $y + 4) . '" text-anchor="end">' . $v . '</text>';
    }
    return $out;
}

/**
 * Bars: one for every day, week or month.
 *
 * @param list<array{label: string, tip: string, value: int}> $points
 */
function chart_bars(array $points, int $colour, string $about): string
{
    $w = 480.0;
    $h = 210.0;
    $left = 38.0;
    $right = $w - 6.0;
    $y0 = 16.0;
    $ph = $h - $y0 - 26.0;
    $n = max(1, count($points));
    $max = 1;
    foreach ($points as $p) {
        $max = max($max, $p['value']);
    }
    $step = chart_step($max);
    $top = $step * (int) ceil($max / $step);
    $slot = ($right - $left) / $n;
    $bw = max(2.0, min(40.0, $slot * 0.72));
    $every = (int) ceil($n / 7);
    $out = '<svg class="chart" viewBox="0 0 480 210" role="img" aria-label="' . e($about) . '">' . chart_grid($top, $step, $left, $right, $y0, $ph);
    foreach ($points as $i => $p) {
        $x = $left + $slot * $i + ($slot - $bw) / 2;
        if ($p['value'] > 0) {
            $bh = max(2.0, $ph * $p['value'] / $top);
            $out .= '<rect class="bar c' . $colour . '" x="' . chart_num($x) . '" y="' . chart_num($y0 + $ph - $bh) . '" width="' . chart_num($bw) . '" height="' . chart_num($bh) . '" rx="' . chart_num(min(3.0, $bw / 2)) . '"><title>' . e($p['tip']) . '</title></rect>';
            if ($n <= 14) {
                $out .= '<text class="value" x="' . chart_num($x + $bw / 2) . '" y="' . chart_num($y0 + $ph - $bh - 4) . '" text-anchor="middle">' . $p['value'] . '</text>';
            }
        }
        // the labels are counted from the right edge: the last bar (today) always has one
        if (($n - 1 - $i) % $every === 0) {
            // the last one ends where its bar ends, so that it stays inside the picture
            $last = $i === $n - 1 && $n > 1;
            $out .= '<text class="tick" x="' . chart_num($last ? min($right, $x + $bw + 4) : $x + $bw / 2) . '" y="' . chart_num($h - 8) . '" text-anchor="' . ($last ? 'end' : 'middle') . '">' . e($p['label']) . '</text>';
        }
    }
    return $out . '</svg>';
}

/**
 * Lines that only go up: how many there were in all on every day.
 *
 * @param array<string, list<int>> $series name => the running total of every day
 * @param list<string> $labels a label for every day
 */
function chart_lines(array $series, array $labels, string $about): string
{
    $w = 480.0;
    $h = 210.0;
    $left = 38.0;
    $right = $w - 10.0;
    $y0 = 16.0;
    $ph = $h - $y0 - 26.0;
    $n = count($labels);
    $max = 1;
    foreach ($series as $values) {
        $max = max($max, $values ? max($values) : 0);
    }
    $step = chart_step($max);
    $top = $step * (int) ceil($max / $step);
    $out = '<svg class="chart" viewBox="0 0 480 210" role="img" aria-label="' . e($about) . '">' . chart_grid($top, $step, $left, $right, $y0, $ph);
    $at = static fn (int $i): float => $n > 1 ? $left + ($right - $left) * $i / ($n - 1) : ($left + $right) / 2;
    $k = 0;
    foreach ($series as $name => $values) {
        $k++;
        $points = [];
        foreach ($values as $i => $v) {
            $points[] = chart_num($at($i)) . ',' . chart_num($y0 + $ph - $ph * $v / $top);
        }
        if ($points) {
            $last = explode(',', (string) end($points));
            $out .= '<polyline class="line l' . $k . '" points="' . implode(' ', $points) . '"><title>' . e($name . ': ' . end($values)) . '</title></polyline>'
                . '<circle class="dot c' . $k . '" cx="' . $last[0] . '" cy="' . $last[1] . '" r="3.5"/>';
        }
    }
    foreach (array_unique([0, intdiv($n - 1, 2), $n - 1]) as $i) {
        if ($i >= 0 && isset($labels[$i])) {
            $anchor = $i === 0 ? 'start' : ($i === $n - 1 ? 'end' : 'middle');
            $out .= '<text class="tick" x="' . chart_num($at($i)) . '" y="' . chart_num($h - 8) . '" text-anchor="' . $anchor . '">' . e($labels[$i]) . '</text>';
        }
    }
    return $out . '</svg>';
}

/**
 * A ring cut into shares, with its legend.
 *
 * @param array<string, int> $items what => how many
 */
function chart_donut(array $items, string $about): string
{
    $items = array_filter($items, static fn ($c) => $c > 0);
    $total = array_sum($items);
    if ($total === 0) {
        return '<p class="note">За этот период данных нет.</p>';
    }
    arsort($items);
    if (count($items) > 6) {
        $rest = array_sum(array_slice($items, 5, null, true));
        $items = array_slice($items, 0, 5, true);
        $items['другое'] = $rest;
    }
    $svg = '<svg class="donut" viewBox="0 0 42 42" role="img" aria-label="' . e($about) . '"><circle class="ring" cx="21" cy="21" r="15.9155"/>';
    $legend = '<ul class="legend">';
    $before = 0.0;
    $k = 0;
    foreach ($items as $name => $c) {
        $k++;
        $share = $c * 100 / $total;
        // the ring is 100 long; a quarter turn back puts the start at the top
        $svg .= '<circle class="seg s' . $k . '" cx="21" cy="21" r="15.9155" stroke-dasharray="' . chart_num(round($share, 1)) . ' ' . chart_num(round(100 - $share, 1)) . '" stroke-dashoffset="' . chart_num(round(125 - $before, 1)) . '"><title>' . e($name . ': ' . $c) . '</title></circle>';
        $legend .= '<li><i class="key k' . $k . '"></i><span>' . e((string) $name) . '</span><b>' . $c . '</b><small>' . (int) round($share) . '%</small></li>';
        $before += $share;
    }
    $svg .= '<text class="total" x="21" y="23.6" text-anchor="middle">' . $total . '</text></svg>';
    return '<div class="donut-row">' . $svg . $legend . '</ul></div>';
}

/** One card of the statistics page. */
function chart_card(string $title, string $note, string $body): string
{
    return '<figure class="chart-card"><figcaption><b>' . e($title) . '</b>' . ($note !== '' ? '<span>' . e($note) . '</span>' : '') . '</figcaption>' . $body . '</figure>';
}

/**
 * The statistics tab: what happened day by day, how the totals grew, and what the totals are made of.
 *
 * @param array<string, string> $kinds the kinds of feedback, key => name
 */
function stats_view(PDO $pdo, int $days, array $kinds): string
{
    $zoneName = (string) (Site::$config['timezone'] ?? 'Europe/Minsk');
    try {
        $zone = new DateTimeZone($zoneName);
    } catch (Throwable $e) {
        $zone = new DateTimeZone('Europe/Minsk');
    }
    $utc = new DateTimeZone('UTC');
    $now = new DateTimeImmutable('now', $zone);
    $today = $now->setTime(0, 0);
    // rows are kept in UTC; days are counted the way the owner lives them
    $shift = (int) $zone->getOffset($now);
    $local = 'DATE(DATE_ADD(created_at, INTERVAL ' . $shift . ' SECOND))';

    // ---- the buckets of the chosen period
    $buckets = [];   // first day (Y-m-d) => [label, tip]
    if ($days === 365) {
        $first = $today->modify('first day of this month')->modify('-11 months');
        for ($m = $first; $m <= $today; $m = $m->modify('+1 month')) {
            $i = (int) $m->format('n') - 1;
            $buckets[$m->format('Y-m-d')] = [CHART_MONTHS_SHORT[$i], CHART_MONTHS_SHORT[$i] . ' ' . $m->format('Y')];
        }
        $unit = 'по месяцам';
    } elseif ($days === 90) {
        $first = $today->modify('-90 days');
        for ($d = $first; $d <= $today; $d = $d->modify('+7 days')) {
            $end = $d->modify('+6 days');
            $buckets[$d->format('Y-m-d')] = [$d->format('j.m'), $d->format('j') . ' ' . CHART_MONTHS[(int) $d->format('n') - 1] . ' – ' . $end->format('j') . ' ' . CHART_MONTHS[(int) $end->format('n') - 1]];
        }
        $unit = 'по неделям';
    } else {
        $first = $today->modify('-' . ($days - 1) . ' days');
        for ($d = $first; $d <= $today; $d = $d->modify('+1 day')) {
            $buckets[$d->format('Y-m-d')] = [$d->format('j.m'), $d->format('j') . ' ' . CHART_MONTHS[(int) $d->format('n') - 1]];
        }
        $unit = 'по дням';
    }
    $starts = array_keys($buckets);
    $since = $first->setTimezone($utc)->format('Y-m-d H:i:s');
    /** Which bucket a day belongs to. */
    $bucket = static function (string $day) use ($starts): ?string {
        $found = null;
        foreach ($starts as $s) {
            if ($s <= $day) {
                $found = $s;
            } else {
                break;
            }
        }
        return $found;
    };
    $perDay = static function (string $table, string $kind) use ($pdo, $local, $since, $bucket, $starts): array {
        $out = array_fill_keys($starts, 0);
        $st = $pdo->prepare("SELECT $local AS d, COUNT(*) AS c FROM $table WHERE kind = ? AND created_at >= ? GROUP BY d");
        $st->execute([$kind, $since]);
        foreach ($st->fetchAll() as $row) {
            $b = $bucket((string) $row['d']);
            if ($b !== null) {
                $out[$b] += (int) $row['c'];
            }
        }
        return $out;
    };

    $series = [
        ['Скачивания', $perDay('mps_events', 'download'), 1, ['скачивание', 'скачивания', 'скачиваний']],
        ['Анкеты', $perDay('mps_responses', 'survey'), 2, ['анкета', 'анкеты', 'анкет']],
        ['Отзывы', $perDay('mps_responses', 'feedback'), 3, ['отзыв', 'отзыва', 'отзывов']],
    ];
    $periods = [7 => '7 дней', 30 => '30 дней', 90 => '90 дней', 365 => 'Год'];
    $out = '<nav class="period" aria-label="Период">';
    foreach ($periods as $value => $name) {
        $out .= '<a href="/admin/?tab=stats&amp;days=' . $value . '"' . ($value === $days ? ' aria-current="true"' : '') . '>' . $name . '</a>';
    }
    $out .= '</nav><h2>Что происходило ' . $unit . '</h2><div class="charts">';
    foreach ($series as [$title, $values, $colour, $words]) {
        $points = [];
        $total = array_sum($values);
        $best = null;
        foreach ($values as $start => $v) {
            $points[] = ['label' => $buckets[$start][0], 'tip' => $buckets[$start][1] . ': ' . $v, 'value' => $v];
            if ($v > 0 && ($best === null || $v > $values[$best])) {
                $best = $start;
            }
        }
        $note = 'За период: ' . $total;
        if ($best !== null && $total > $values[$best]) {
            $note .= ' · больше всего: ' . $values[$best] . ' (' . $buckets[$best][1] . ')';
        }
        $table = '<details class="numbers"><summary>Числа</summary><table><tbody>';
        foreach (array_reverse($values, true) as $start => $v) {
            $table .= '<tr><th scope="row">' . e($buckets[$start][1]) . '</th><td class="num">' . $v . '</td></tr>';
        }
        $table .= '</tbody></table></details>';
        $about = $title . ' ' . $unit . ': всего ' . $total . ' ' . chart_plural($total, ...$words);
        $out .= chart_card($title, $note, chart_bars($points, $colour, $about) . $table);
    }

    // ---- how the totals grew, from the first day anything was kept
    $grow = [];
    $firstDay = null;
    foreach ([['Скачивания', 'mps_events', 'download'], ['Анкеты', 'mps_responses', 'survey'], ['Отзывы', 'mps_responses', 'feedback']] as [$name, $table, $kind]) {
        $st = $pdo->prepare("SELECT $local AS d, COUNT(*) AS c FROM $table WHERE kind = ? GROUP BY d ORDER BY d");
        $st->execute([$kind]);
        $grow[$name] = [];
        foreach ($st->fetchAll() as $row) {
            $grow[$name][(string) $row['d']] = (int) $row['c'];
            if ($firstDay === null || (string) $row['d'] < $firstDay) {
                $firstDay = (string) $row['d'];
            }
        }
    }
    if ($firstDay === null) {
        $out .= chart_card('Всего с начала', '', '<p class="note">Пока нечего показывать: нет ни скачиваний, ни ответов.</p>');
    } else {
        $from = new DateTimeImmutable($firstDay, $zone);
        // a line needs at least a few days to be a line
        $from = min($from, $today->modify('-6 days'));
        $labels = [];
        $lines = [];
        $running = array_fill_keys(array_keys($grow), 0);
        for ($d = $from; $d <= $today; $d = $d->modify('+1 day')) {
            $key = $d->format('Y-m-d');
            $labels[] = $d->format('j.m') . ($d->format('Y') !== $today->format('Y') ? '.' . $d->format('y') : '');
            foreach ($grow as $name => $perDayAll) {
                $running[$name] += $perDayAll[$key] ?? 0;
                $lines[$name][] = $running[$name];
            }
        }
        $legend = '<ul class="legend inline">';
        $k = 0;
        foreach ($running as $name => $v) {
            $legend .= '<li><i class="key k' . (++$k) . '"></i><span>' . e($name) . '</span><b>' . $v . '</b></li>';
        }
        $legend .= '</ul>';
        $out .= chart_card('Всего с начала', 'с ' . $from->format('j') . ' ' . CHART_MONTHS[(int) $from->format('n') - 1] . ' ' . $from->format('Y'),
            chart_lines($lines, $labels, 'Итоги нарастающим числом: ' . implode(', ', array_map(static fn ($n, $v) => $n . ' — ' . $v, array_keys($running), $running))) . $legend);
    }
    $out .= '</div>';

    // ---- what the totals of the period are made of
    $group = static function (string $sql, array $args) use ($pdo): array {
        $st = $pdo->prepare($sql);
        $st->execute($args);
        $out = [];
        foreach ($st->fetchAll(PDO::FETCH_NUM) as $row) {
            $out[(string) $row[0]] = (int) $row[1];
        }
        return $out;
    };
    $builds = [];
    foreach ($group("SELECT detail, COUNT(*) FROM mps_events WHERE kind = 'download' AND created_at >= ? GROUP BY detail", [$since]) as $file => $c) {
        $name = preg_match('/(\d+(?:\.\d+)+)/', $file, $m) ? 'версия ' . $m[1] : ($file !== '' ? $file : 'без названия');
        $builds[$name] = ($builds[$name] ?? 0) + $c;
    }
    $sources = [];
    foreach ($group("SELECT source, COUNT(*) FROM mps_responses WHERE kind = 'survey' AND created_at >= ? GROUP BY source", [$since]) as $source => $c) {
        $sources[$source !== '' ? $source : 'без метки'] = $c;
    }
    $languages = [];
    foreach ($group('SELECT lang, COUNT(*) FROM mps_responses WHERE created_at >= ? GROUP BY lang', [$since]) as $lang => $c) {
        $languages[['ru' => 'русский', 'en' => 'английский'][$lang] ?? ($lang !== '' ? $lang : 'не указан')] = $c;
    }
    $types = [];
    $versions = [];
    $st = $pdo->prepare("SELECT answers, app_version FROM mps_responses WHERE kind = 'feedback' AND created_at >= ?");
    $st->execute([$since]);
    foreach ($st->fetchAll() as $row) {
        $a = json_decode((string) $row['answers'], true) ?: [];
        $type = $kinds[$a['kind'] ?? ''] ?? 'другое';
        $types[$type] = ($types[$type] ?? 0) + 1;
        $version = (string) $row['app_version'] !== '' ? 'версия ' . $row['app_version'] : 'с сайта';
        $versions[$version] = ($versions[$version] ?? 0) + 1;
    }
    $period = mb_strtolower($periods[$days]);
    $out .= '<h2>Из чего складываются итоги</h2><div class="charts small">'
        . chart_card('Что скачивали', 'за ' . $period, chart_donut($builds, 'Скачивания по версиям приложения'))
        . chart_card('Откуда пришли анкеты', 'за ' . $period . ' · метка ?src= в ссылке на опрос', chart_donut($sources, 'Анкеты по источникам'))
        . chart_card('О чём отзывы', 'за ' . $period, chart_donut($types, 'Отзывы по видам'))
        . chart_card('Откуда отзывы', 'за ' . $period . ' · версия приложения, из которого писали', chart_donut($versions, 'Отзывы по версиям приложения'))
        . chart_card('Язык ответов', 'анкеты и отзывы за ' . $period, chart_donut($languages, 'Ответы по языкам'))
        . '</div>';
    $zoneNote = $shift === 0 ? 'UTC' : 'UTC' . ($shift > 0 ? '+' : '−') . chart_num(abs($shift) / 3600);
    return $out . '<p class="note">Дни считаются по времени ' . e($zoneNote) . '. Диаграммы строятся по тем же записям, что и числа наверху: скачивание считается один раз в час на посетителя, анкеты и отзывы — каждая запись. Посещения страниц сайт не считает.</p>';
}
