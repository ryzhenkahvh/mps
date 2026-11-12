<?php
declare(strict_types=1);
if (!defined('MPS')) {
    http_response_code(404);
    exit;
}

/**
 * Server-rendered SVG charts for /stats/ (public aggregate) and /admin/ (owner analytics).
 * The site CSP forbids inline <style> and inline <script> inside HTML; colours come from site.css.
 */

const CHART_MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];
const CHART_MONTHS_SHORT = ['янв', 'фев', 'мар', 'апр', 'май', 'июн', 'июл', 'авг', 'сен', 'окт', 'ноя', 'дек'];

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
    return rtrim(rtrim(number_format($v, 2, '.', ''), '0'), '.');
}

function chart_plural(int $n, string $one, string $few, string $many): string
{
    $n10 = $n % 10;
    $n100 = $n % 100;
    if ($n10 === 1 && $n100 !== 11) {
        return $one;
    }
    return $n10 >= 2 && $n10 <= 4 && ($n100 < 12 || $n100 > 14) ? $few : $many;
}

/** Wraps a label onto at most $maxLines lines without breaking words mid-character. */
function chart_wrap(string $text, int $maxChars = 32, int $maxLines = 2): array
{
    $text = trim(preg_replace('/\s+/u', ' ', $text) ?? $text);
    if ($text === '') {
        return [''];
    }
    $words = preg_split('/\s+/u', $text) ?: [$text];
    $lines = [];
    $cur = '';
    foreach ($words as $w) {
        $try = $cur === '' ? $w : ($cur . ' ' . $w);
        if (mb_strlen($try, 'UTF-8') <= $maxChars) {
            $cur = $try;
        } else {
            if ($cur !== '') {
                $lines[] = $cur;
                if (count($lines) >= $maxLines) {
                    $last = $lines[$maxLines - 1];
                    if (mb_strlen($last, 'UTF-8') > $maxChars - 1) {
                        $last = mb_substr($last, 0, $maxChars - 1, 'UTF-8');
                    }
                    $lines[$maxLines - 1] = rtrim($last) . '…';
                    return $lines;
                }
            }
            if (mb_strlen($w, 'UTF-8') > $maxChars) {
                $cur = mb_substr($w, 0, $maxChars - 1, 'UTF-8') . '…';
            } else {
                $cur = $w;
            }
        }
    }
    if ($cur !== '' && count($lines) < $maxLines) {
        $lines[] = $cur;
    }
    return $lines ?: [$text];
}

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
 * Vertical time-series bar chart.
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
            $out .= '<rect class="c' . $colour . '" x="' . chart_num($x) . '" y="' . chart_num($y0 + $ph - $bh) . '" width="' . chart_num($bw) . '" height="' . chart_num($bh) . '" rx="' . chart_num(min(3.0, $bw / 2)) . '"><title>' . e($p['tip']) . '</title></rect>';
            if ($n <= 14) {
                $out .= '<text class="value" x="' . chart_num($x + $bw / 2) . '" y="' . chart_num($y0 + $ph - $bh - 4) . '" text-anchor="middle">' . $p['value'] . '</text>';
            }
        }
        if (($n - 1 - $i) % $every === 0) {
            $last = $i === $n - 1 && $n > 1;
            $out .= '<text class="tick" x="' . chart_num($last ? min($right, $x + $bw + 4) : $x + $bw / 2) . '" y="' . chart_num($h - 8) . '" text-anchor="' . ($last ? 'end' : 'middle') . '">' . e($p['label']) . '</text>';
        }
    }
    return $out . '</svg>';
}

/**
 * Cumulative running totals line chart.
 *
 * @param array<string, list<int>> $series
 * @param list<string> $labels
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
 * Compact HTML+SVG donut used in /admin/?tab=stats breakdown cards.
 *
 * @param array<string, int> $items
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
        $svg .= '<circle class="seg s' . $k . '" cx="21" cy="21" r="15.9155" stroke-dasharray="' . chart_num(round($share, 1)) . ' ' . chart_num(round(100 - $share, 1)) . '" stroke-dashoffset="' . chart_num(round(125 - $before, 1)) . '"><title>' . e($name . ': ' . $c) . '</title></circle>';
        $legend .= '<li><i class="key k' . $k . '"></i><span>' . e((string) $name) . '</span><b>' . $c . '</b><small>' . (int) round($share) . '%</small></li>';
        $before += $share;
    }
    $svg .= '<text class="total" x="21" y="23.6" text-anchor="middle">' . $total . '</text></svg>';
    return '<div class="donut-row">' . $svg . $legend . '</ul></div>';
}

/**
 * Self-contained presentation horizontal bar chart SVG.
 *
 * @param list<array{label: string, count: int, pct: float, cls: string}> $rows
 */
function chart_hbar(array $rows, string $about): string
{
    $n = count($rows);
    if ($n === 0) {
        return '';
    }
    $left = 250.0;
    $barMax = 318.0;
    $right = $left + $barMax;
    $rowH = 44.0;
    $topY = 24.0;
    $h = (int) ($topY + $n * $rowH + 10);
    $out = '<svg class="chart hbar" viewBox="0 0 680 ' . $h . '" role="img" aria-label="' . e($about) . '">';
    foreach ([0, 25, 50, 75, 100] as $p) {
        $gx = $left + $barMax * $p / 100.0;
        $anchor = $p === 0 ? 'start' : ($p === 100 ? 'end' : 'middle');
        $out .= '<line class="grid" x1="' . chart_num($gx) . '" x2="' . chart_num($gx) . '" y1="18" y2="' . ($h - 8) . '"/>'
            . '<text class="tick" x="' . chart_num($gx) . '" y="12" text-anchor="' . $anchor . '">' . $p . '%</text>';
    }
    foreach ($rows as $i => $row) {
        $y0 = $topY + $i * $rowH;
        $lines = chart_wrap((string) $row['label'], 33, 2);
        if (count($lines) === 1) {
            $out .= '<text class="hbar-lbl" x="0" y="' . chart_num($y0 + 26) . '">' . e($lines[0]) . '</text>';
        } else {
            $out .= '<text class="hbar-lbl" x="0" y="' . chart_num($y0 + 18) . '">' . e($lines[0])
                . '<tspan x="0" dy="15">' . e($lines[1]) . '</tspan></text>';
        }
        $pct = max(0.0, min(100.0, (float) $row['pct']));
        $count = (int) $row['count'];
        $bw = $count > 0 ? max(4.0, $barMax * $pct / 100.0) : 0.0;
        $out .= '<rect class="track" x="' . chart_num($left) . '" y="' . chart_num($y0 + 12) . '" width="' . chart_num($barMax) . '" height="18" rx="6"/>';
        if ($bw > 0) {
            $out .= '<rect class="' . e($row['cls']) . '" x="' . chart_num($left) . '" y="' . chart_num($y0 + 12) . '" width="' . chart_num($bw) . '" height="18" rx="6"><title>' . e($row['label'] . ': ' . $count . ' (' . round($pct, 1) . '%)') . '</title></rect>';
        }
        $pctStr = (int) round($pct) . '%';
        $out .= '<text class="hbar-val" x="' . chart_num($right + 10) . '" y="' . chart_num($y0 + 25) . '">'
            . $pctStr . ' (' . $count . ')</text>';
    }
    return $out . '</svg>';
}

/**
 * Self-contained presentation donut SVG (ring + legend inside one SVG so PNG/SVG export is complete).
 *
 * @param list<array{label: string, count: int, pct: float, cls: string, keyCls: string}> $slices
 */
function chart_donut_svg(array $slices, int $total, string $centerSub, string $about): string
{
    $slices = array_values(array_filter($slices, static fn ($s) => $s['count'] > 0));
    if ($total <= 0 || !$slices) {
        return '';
    }
    $rOut = 78.0;
    $rIn = 52.0;
    $cx = 112.0;
    $rows = count($slices);
    $rowH = 42.0;
    $h = max(216, (int) (36 + $rows * $rowH));
    $cy = $h / 2.0;
    $svg = '<svg class="chart donut-pres" viewBox="0 0 680 ' . $h . '" role="img" aria-label="' . e($about) . '">'
        . '<circle class="track" cx="' . chart_num($cx) . '" cy="' . chart_num($cy) . '" r="' . chart_num(($rOut + $rIn) / 2.0) . '" fill="none" stroke-width="' . chart_num($rOut - $rIn) . '"/>';
    $a0 = -M_PI / 2.0;
    $legendY = max(26.0, ($h - ($rows - 1) * $rowH - 16) / 2);
    foreach ($slices as $i => $s) {
        $frac = $s['count'] / $total;
        $sweep = 2.0 * M_PI * $frac;
        $a1 = $a0 + $sweep;
        $tip = '<title>' . e($s['label'] . ': ' . $s['count'] . ' (' . round($s['pct']) . '%)') . '</title>';
        if ($frac >= 0.9999) {
            // 100% single slice: draw two semicircular arcs for universal SVG compatibility
            $d = sprintf(
                'M %s %s A %s %s 0 1 1 %s %s A %s %s 0 1 1 %s %s Z M %s %s A %s %s 0 1 0 %s %s A %s %s 0 1 0 %s %s Z',
                chart_num($cx), chart_num($cy - $rOut), chart_num($rOut), chart_num($rOut), chart_num($cx), chart_num($cy + $rOut),
                chart_num($rOut), chart_num($rOut), chart_num($cx), chart_num($cy - $rOut),
                chart_num($cx), chart_num($cy - $rIn), chart_num($rIn), chart_num($rIn), chart_num($cx), chart_num($cy + $rIn),
                chart_num($rIn), chart_num($rIn), chart_num($cx), chart_num($cy - $rIn)
            );
            $svg .= '<path class="' . e($s['keyCls']) . '" fill-rule="evenodd" d="' . $d . '">' . $tip . '</path>';
        } else {
            $large = $sweep > M_PI ? 1 : 0;
            $ox1 = $cx + $rOut * cos($a0);
            $oy1 = $cy + $rOut * sin($a0);
            $ox2 = $cx + $rOut * cos($a1);
            $oy2 = $cy + $rOut * sin($a1);
            $ix1 = $cx + $rIn * cos($a1);
            $iy1 = $cy + $rIn * sin($a1);
            $ix2 = $cx + $rIn * cos($a0);
            $iy2 = $cy + $rIn * sin($a0);
            $d = 'M ' . chart_num($ox1) . ' ' . chart_num($oy1)
                . ' A ' . chart_num($rOut) . ' ' . chart_num($rOut) . ' 0 ' . $large . ' 1 ' . chart_num($ox2) . ' ' . chart_num($oy2)
                . ' L ' . chart_num($ix1) . ' ' . chart_num($iy1)
                . ' A ' . chart_num($rIn) . ' ' . chart_num($rIn) . ' 0 ' . $large . ' 0 ' . chart_num($ix2) . ' ' . chart_num($iy2) . ' Z';
            $svg .= '<path class="' . e($s['keyCls']) . '" d="' . $d . '">' . $tip . '</path>';
        }
        $a0 = $a1;

        $ly = $legendY + $i * $rowH;
        $lines = chart_wrap((string) $s['label'], 33, 2);
        $svg .= '<rect class="' . e($s['keyCls']) . '" x="226" y="' . chart_num($ly) . '" width="14" height="14" rx="4"/>';
        if (count($lines) === 1) {
            $svg .= '<text class="hbar-lbl" x="250" y="' . chart_num($ly + 11.5) . '">' . e($lines[0]) . '</text>';
        } else {
            $svg .= '<text class="hbar-lbl" x="250" y="' . chart_num($ly + 8) . '">' . e($lines[0])
                . '<tspan x="250" dy="15">' . e($lines[1]) . '</tspan></text>';
        }
        $svg .= '<text class="hbar-val" x="674" y="' . chart_num($ly + 11.5) . '" text-anchor="end">'
            . (int) round($s['pct']) . '% (' . $s['count'] . ')</text>';
    }
    $svg .= '<text class="donut-center-num" x="' . chart_num($cx) . '" y="' . chart_num($cy + 4) . '" text-anchor="middle">' . $total . '</text>'
        . '<text class="tick" x="' . chart_num($cx) . '" y="' . chart_num($cy + 21) . '" text-anchor="middle">' . e($centerSub) . '</text>';
    return $svg . '</svg>';
}

/**
 * Self-contained NPS 0..10 histogram + segmented summary bar.
 *
 * @param array<int, int> $counts map 0..10 => count
 */
function chart_nps_svg(array $counts, string $lang, string $about): string
{
    $total = array_sum($counts);
    if ($total <= 0) {
        return '';
    }
    $w = 680.0;
    $left = 40.0;
    $right = $w - 16.0;
    $y0 = 42.0;
    $ph = 136.0;
    $max = max(1, max($counts));
    $step = chart_step($max);
    $top = $step * (int) ceil($max / $step);
    $slot = ($right - $left) / 11.0;
    $bw = 36.0;

    $det = 0;
    $pas = 0;
    $pro = 0;
    $sum = 0;
    for ($s = 0; $s <= 10; $s++) {
        $c = $counts[$s] ?? 0;
        $sum += $s * $c;
        if ($s <= 6) {
            $det += $c;
        } elseif ($s <= 8) {
            $pas += $c;
        } else {
            $pro += $c;
        }
    }
    $mean = $sum / $total;
    $nps = (int) round(($pro - $det) * 100 / $total);
    $npsStr = ($nps > 0 ? '+' : '') . $nps;

    $svg = '<svg class="chart" viewBox="0 0 680 266" role="img" aria-label="' . e($about) . '">' . chart_grid($top, $step, $left, $right, $y0, $ph);
    for ($s = 0; $s <= 10; $s++) {
        $c = $counts[$s] ?? 0;
        $x = $left + $slot * $s + ($slot - $bw) / 2;
        $cls = $s <= 6 ? 'nps-det' : ($s <= 8 ? 'nps-pas' : 'nps-pro');
        if ($c > 0) {
            $bh = max(4.0, $ph * $c / $top);
            $svg .= '<rect class="' . $cls . '" x="' . chart_num($x) . '" y="' . chart_num($y0 + $ph - $bh) . '" width="' . chart_num($bw) . '" height="' . chart_num($bh) . '" rx="5"><title>' . $s . ': ' . $c . '</title></rect>'
                . '<text class="value" x="' . chart_num($x + $bw / 2) . '" y="' . chart_num($y0 + $ph - $bh - 6) . '" text-anchor="middle">' . $c . '</text>';
        } else {
            $svg .= '<rect class="track" x="' . chart_num($x) . '" y="' . chart_num($y0 + $ph - 3) . '" width="' . chart_num($bw) . '" height="3" rx="1.5"/>';
        }
        $svg .= '<text class="tick" x="' . chart_num($x + $bw / 2) . '" y="' . chart_num($y0 + $ph + 18) . '" text-anchor="middle">' . $s . '</text>';
    }

    // 100% segmented summary bar
    $barY = 212.0;
    $barW = $right - $left;
    $wDet = $barW * $det / $total;
    $wPas = $barW * $pas / $total;
    $wPro = max(0.0, $barW - $wDet - $wPas);
    $xPos = $left;
    foreach ([
        [$wDet, 'nps-det', $det],
        [$wPas, 'nps-pas', $pas],
        [$wPro, 'nps-pro', $pro],
    ] as [$segW, $segCls, $segC]) {
        if ($segW > 0.5) {
            $svg .= '<rect class="' . $segCls . '" x="' . chart_num($xPos) . '" y="' . chart_num($barY) . '" width="' . chart_num($segW) . '" height="16"/>';
            if ($segW >= 42) {
                $svg .= '<text class="seg-lbl" x="' . chart_num($xPos + $segW / 2) . '" y="' . chart_num($barY + 12) . '" text-anchor="middle">' . (int) round($segC * 100 / $total) . '%</text>';
            }
            $xPos += $segW;
        }
    }

    $lblDet = $lang === 'en' ? 'Detractors 0–6' : 'Критики 0–6';
    $lblPas = $lang === 'en' ? 'Passives 7–8' : 'Нейтралы 7–8';
    $lblPro = $lang === 'en' ? 'Promoters 9–10' : 'Сторонники 9–10';
    $lblAvg = $lang === 'en' ? 'Mean' : 'Средняя';

    $ly = 250.0;
    $items = [
        ['nps-det', $lblDet . ': ' . (int) round($det * 100 / $total) . '% (' . $det . ')', 40.0],
        ['nps-pas', $lblPas . ': ' . (int) round($pas * 100 / $total) . '% (' . $pas . ')', 250.0],
        ['nps-pro', $lblPro . ': ' . (int) round($pro * 100 / $total) . '% (' . $pro . ')', 460.0],
    ];
    foreach ($items as [$cls, $txt, $ix]) {
        $svg .= '<rect class="' . $cls . '" x="' . chart_num($ix) . '" y="' . chart_num($ly - 10) . '" width="10" height="10" rx="3"/>'
            . '<text class="tick" x="' . chart_num($ix + 15) . '" y="' . chart_num($ly - 1) . '">' . e($txt) . '</text>';
    }
    $svg .= '<text class="value" x="' . chart_num($right) . '" y="16" text-anchor="end">'
        . e($lblAvg . ' ' . number_format($mean, 1, $lang === 'en' ? '.' : ',', '') . '/10 · NPS ' . $npsStr) . '</text>';

    return $svg . '</svg>';
}

/**
 * Self-contained 100% stacked horizontal bars for cross-tab insights.
 *
 * @param list<array{label: string, total: int, counts: array<string, int>}> $rows
 * @param list<array{key: string, label: string, cls: string}> $segments
 */
function chart_stacked_svg(array $rows, array $segments, string $about): string
{
    $rows = array_values(array_filter($rows, static fn ($r) => $r['total'] > 0));
    if (!$rows) {
        return '';
    }
    $left = 232.0;
    $barW = 438.0;
    $rowH = 44.0;

    $legendSvg = '';
    $lx = 0.0;
    $ly = 6.0;
    foreach ($segments as $seg) {
        $itemW = max(96.0, 26.0 + mb_strlen((string) $seg['label'], 'UTF-8') * 6.8);
        if ($lx > 0.0 && $lx + $itemW > 678.0) {
            $lx = 0.0;
            $ly += 20.0;
        }
        $legendSvg .= '<rect class="' . e($seg['cls']) . '" x="' . chart_num($lx) . '" y="' . chart_num($ly) . '" width="12" height="12" rx="3"/>'
            . '<text class="tick" x="' . chart_num($lx + 17) . '" y="' . chart_num($ly + 10) . '">' . e($seg['label']) . '</text>';
        $lx += $itemW;
    }

    $topY = $ly + 28.0;
    $h = (int) ($topY + count($rows) * $rowH + 10);
    $svg = '<svg class="chart" viewBox="0 0 680 ' . $h . '" role="img" aria-label="' . e($about) . '">' . $legendSvg;

    foreach ($rows as $i => $row) {
        $y0 = $topY + $i * $rowH;
        $labelText = $row['label'] . ' (n=' . $row['total'] . ')';
        $lines = chart_wrap($labelText, 30, 2);
        if (count($lines) === 1) {
            $svg .= '<text class="hbar-lbl" x="0" y="' . chart_num($y0 + 25) . '">' . e($lines[0]) . '</text>';
        } else {
            $svg .= '<text class="hbar-lbl" x="0" y="' . chart_num($y0 + 17) . '">' . e($lines[0])
                . '<tspan x="0" dy="15">' . e($lines[1]) . '</tspan></text>';
        }
        $xPos = $left;
        $tot = max(1, $row['total']);
        $lastIdx = count($segments) - 1;
        foreach ($segments as $si => $seg) {
            $c = $row['counts'][$seg['key']] ?? 0;
            $wSeg = $si === $lastIdx ? max(0.0, ($left + $barW) - $xPos) : ($barW * $c / $tot);
            if ($wSeg > 0.5 && $c > 0) {
                $svg .= '<rect class="' . e($seg['cls']) . '" x="' . chart_num($xPos) . '" y="' . chart_num($y0 + 10) . '" width="' . chart_num($wSeg) . '" height="22"><title>' . e($row['label'] . ' — ' . $seg['label'] . ': ' . $c . ' (' . round($c * 100 / $tot) . '%)') . '</title></rect>';
                if ($wSeg >= 34) {
                    $svg .= '<text class="seg-lbl" x="' . chart_num($xPos + $wSeg / 2) . '" y="' . chart_num($y0 + 25) . '" text-anchor="middle">' . (int) round($c * 100 / $tot) . '%</text>';
                }
                $xPos += $wSeg;
            }
        }
    }
    return $svg . '</svg>';
}

/** Wraps one chart inside a presentation card with PNG/SVG/Copy export controls. */
function chart_card(string $title, string $note, string $body, array $opts = []): string
{
    $export = $opts['export'] ?? false;
    $slug = preg_replace('/[^a-z0-9_-]/i', '-', (string) ($opts['slug'] ?? 'chart')) ?? 'chart';
    $lang = (string) ($opts['lang'] ?? 'ru');
    $wide = !empty($opts['wide']) ? ' wide' : '';

    $actions = '';
    if ($export) {
        $lblPng = 'PNG';
        $lblSvg = 'SVG';
        $lblCopy = $lang === 'en' ? 'Copy' : 'Копировать';
        $lblCopied = $lang === 'en' ? 'Copied' : 'Скопировано';
        $actions = '<div class="chart-actions">'
            . '<button type="button" class="mini-btn" data-export-png>' . $lblPng . '</button>'
            . '<button type="button" class="mini-btn" data-export-svg>' . $lblSvg . '</button>'
            . '<button type="button" class="mini-btn" data-export-copy data-copied="' . e($lblCopied) . '">' . $lblCopy . '</button>'
            . '</div>';
    }
    $attrs = ' class="chart-card' . $wide . '"'
        . ' data-chart-title="' . e($title) . '"'
        . ' data-chart-note="' . e($note) . '"'
        . ' data-chart-slug="' . e($slug) . '"';

    return '<figure' . $attrs . '><figcaption><div class="cap-text"><b>' . e($title) . '</b>'
        . ($note !== '' ? '<span>' . e($note) . '</span>' : '')
        . '</div>' . $actions . '</figcaption>' . $body . '</figure>';
}

/**
 * Presentation-ready survey analytics shared by public /stats/ and private /admin/.
 * Only processes closed/scale answers; never reads or outputs contacts or personal text.
 *
 * @param list<array{answers: string, lang?: string, created_at?: string}> $allRows
 * @param list<array<string, mixed>> $questions
 */
function survey_presentation_stats(array $allRows, array $questions, string $lang, array $opts = []): string
{
    $baseUrl = (string) ($opts['baseUrl'] ?? Site::url('stats'));
    $segment = (string) ($opts['segment'] ?? 'all');
    $qrUrl = (string) ($opts['qrUrl'] ?? (rtrim((string) Site::$config['site_url'], '/') . Site::url('stats', $lang)));
    $downloads = $opts['downloads'] ?? null;
    $isEn = $lang === 'en';

    $parsed = [];
    foreach ($allRows as $row) {
        $a = json_decode((string) ($row['answers'] ?? ''), true);
        if (is_array($a)) {
            $parsed[] = $a;
        }
    }

    $segDefs = [
        'all' => $isEn ? 'All respondents' : 'Все ответы',
        'creators' => $isEn ? 'Making music' : 'Пишут музыку',
        'mobile' => $isEn ? 'Phone / hybrid' : 'На телефоне / в связке',
        'tried' => $isEn ? 'Tried MPS' : 'Пробовали MPS',
    ];
    if (!isset($segDefs[$segment])) {
        $segment = 'all';
    }
    $matchSeg = static function (array $a, string $seg): bool {
        switch ($seg) {
            case 'creators':
                return in_array($a['role'] ?? '', ['start', 'self', 'release', 'pro'], true);
            case 'mobile':
                return in_array($a['where'] ?? '', ['phone', 'tablet', 'mix'], true);
            case 'tried':
                return ($a['tried'] ?? '') === 'yes';
            default:
                return true;
        }
    };

    $segCounts = [];
    foreach (array_keys($segDefs) as $sk) {
        $segCounts[$sk] = count(array_filter($parsed, static fn ($a) => $matchSeg($a, $sk)));
    }
    $rows = array_values(array_filter($parsed, static fn ($a) => $matchSeg($a, $segment)));
    $totalAll = count($parsed);
    $n = count($rows);

    // Index question metadata by id
    $qById = [];
    foreach ($questions as $q) {
        $qById[$q['id']] = $q;
    }

    // Tally every closed/scale question
    $tally = [];
    $npsCounts = array_fill(0, 11, 0);
    $npsVals = [];
    $regularCount = 0;
    $regularAnswered = 0;
    $lostIdeaCount = 0;
    $lostAnswered = 0;
    $paidAnyCount = 0;
    $paidAnswered = 0;
    $triedYes = 0;
    $triedAnswered = 0;

    foreach ($rows as $a) {
        if (isset($a['freq'])) {
            $regularAnswered++;
            if (in_array($a['freq'], ['daily', 'weekly'], true)) {
                $regularCount++;
            }
        }
        if (isset($a['lost'])) {
            $lostAnswered++;
            if (in_array($a['lost'], ['often', 'sometimes'], true)) {
                $lostIdeaCount++;
            }
        }
        if (isset($a['paid'])) {
            $paidAnswered++;
            if (in_array($a['paid'], ['low', 'mid', 'high'], true)) {
                $paidAnyCount++;
            }
        }
        if (isset($a['tried'])) {
            $triedAnswered++;
            if ($a['tried'] === 'yes') {
                $triedYes++;
            }
        }
        foreach ($questions as $q) {
            $id = $q['id'];
            $v = $a[$id] ?? null;
            if ($v === null) {
                continue;
            }
            if ($q['type'] === 'scale') {
                $score = (int) $v;
                if ($score >= 0 && $score <= 10) {
                    $npsVals[] = $score;
                    $npsCounts[$score]++;
                }
            } elseif ($q['type'] === 'one' || $q['type'] === 'many') {
                $tally[$id]['answered'] = ($tally[$id]['answered'] ?? 0) + 1;
                foreach ((array) $v as $optKey) {
                    if (is_string($optKey) && isset($q['options'][$optKey])) {
                        $tally[$id]['options'][$optKey] = ($tally[$id]['options'][$optKey] ?? 0) + 1;
                    }
                }
            }
        }
    }

    // QR Share card HTML (also embeds vector QR so slide PNG exports can stamp corner QR cleanly!)
    $qrSvg = class_exists('QrCode') ? QrCode::svg($qrUrl, 'QR: ' . $qrUrl) : '';
    $shortUrl = preg_replace('#^https?://#', '', $qrUrl) ?? $qrUrl;
    $sep = str_contains($baseUrl, '?') ? '&' : '?';

    $out = '<div class="stats-deck" data-slide-deck data-theme="dark" data-slide-qr="1" data-brand="MPS · ' . e($shortUrl) . '">';

    // ---- Segment pills + presentation toolbar
    $out .= '<div class="deck-bar">';
    $out .= '<nav class="period seg-nav" aria-label="' . e($isEn ? 'Audience segment' : 'Сегмент аудитории') . '">';
    foreach ($segDefs as $sk => $sLabel) {
        $href = $baseUrl . ($sk === 'all' ? '' : ($sep . 'segment=' . $sk));
        $out .= '<a href="' . e($href) . '"' . ($sk === $segment ? ' aria-current="true"' : '') . '>'
            . e($sLabel) . ' <b>(' . $segCounts[$sk] . ')</b></a>';
    }
    $out .= '</nav>';

    $out .= '<div class="slide-tools" role="toolbar" aria-label="' . e($isEn ? 'Slide export tools' : 'Настройки слайдов') . '">'
        . '<div class="theme-switch" role="group">'
        . '<button type="button" class="mini-btn active" data-slide-theme="dark" aria-pressed="true">' . ($isEn ? 'Dark slide' : 'Тёмный слайд') . '</button>'
        . '<button type="button" class="mini-btn" data-slide-theme="light" aria-pressed="false">' . ($isEn ? 'Light slide' : 'Светлый слайд') . '</button>'
        . '</div>'
        . '<label class="qr-toggle"><input type="checkbox" checked data-qr-stamp><span>' . ($isEn ? 'QR on exported slides' : 'QR на скачанном слайде') . '</span></label>'
        . '<button type="button" class="mini-btn" data-print-deck>' . ($isEn ? 'Print / PDF' : 'Печать / PDF') . '</button>'
        . '</div></div>';

    // ---- QR Share Hero Banner
    if ($qrSvg !== '') {
        $qrTitle = $isEn ? 'QR code for everyone' : 'QR-код для презентации и экрана';
        $qrLead = $isEn
            ? 'Anyone can scan this QR code to open the live aggregate statistics page. No contacts or personal answers are shown.'
            : 'Любой участник презентации может отсканировать код и открыть эти графики у себя на телефоне. Личные тексты и контакты скрыты.';
        $out .= '<section class="qr-banner" data-qr-card data-qr-url="' . e($qrUrl) . '" data-qr-caption="' . e($shortUrl) . '">'
            . '<div class="qr-frame" title="' . e($qrUrl) . '">' . $qrSvg . '</div>'
            . '<div class="qr-meta">'
            . '<h2>' . e($qrTitle) . '</h2>'
            . '<p class="note">' . e($qrLead) . '</p>'
            . '<p class="qr-url"><code>' . e($qrUrl) . '</code></p>'
            . '<div class="qr-actions">'
            . '<button type="button" class="button primary" data-qr-png>' . ($isEn ? 'Download QR (PNG)' : 'Скачать QR (PNG)') . '</button>'
            . '<button type="button" class="button ghost" data-qr-svg>' . ($isEn ? 'Download QR (SVG)' : 'Скачать QR (SVG)') . '</button>'
            . '<button type="button" class="button ghost" data-copy-text="' . e($qrUrl) . '" data-copied="' . e($isEn ? 'Link copied' : 'Ссылка скопирована') . '">' . ($isEn ? 'Copy link' : 'Копировать ссылку') . '</button>'
            . '</div></div></section>';
    }

    if ($n === 0) {
        $emptyMsg = $totalAll === 0
            ? ($isEn ? 'No responses have been recorded in this view yet.' : 'Ответов пока нет — первые графики появятся сразу после заполнения анкет.')
            : ($isEn ? 'No responses match this segment yet.' : 'В выбранном сегменте пока нет ответов.');
        $out .= '<div class="card"><p class="lead">' . e($emptyMsg) . '</p>';
        if ($totalAll === 0) {
            $out .= '<p><a class="button primary" href="' . e(Site::url('survey', $lang)) . '">' . e(t('cta_button')) . '</a></p>';
        }
        $out .= '</div></div>';
        return $out;
    }

    // ---- 6 KPI Presentation Summary Cards
    $pct = static fn (int $num, int $den): string => $den > 0 ? (int) round($num * 100 / $den) . '%' : '—';
    $npsCount = count($npsVals);
    $npsMean = $npsCount > 0 ? number_format(array_sum($npsVals) / $npsCount, 1, $isEn ? '.' : ',', '') : '—';
    $npsPro = count(array_filter($npsVals, static fn ($x) => $x >= 9));
    $npsDet = count(array_filter($npsVals, static fn ($x) => $x <= 6));
    $npsScore = $npsCount > 0 ? (($npsPro > $npsDet ? '+' : '') . (int) round(($npsPro - $npsDet) * 100 / $npsCount)) : '—';

    $out .= '<ul class="kpi-grid">';
    $kpis = [
        [(string) $n, $isEn ? 'Survey responses' : 'Анкет в выборке', $segment === 'all' ? ($isEn ? '100% of total' : 'вся база ответов') : ($isEn ? 'of ' . $totalAll . ' total' : 'из ' . $totalAll . ' всего')],
        [$pct($regularCount, $regularAnswered), $isEn ? 'Make music weekly+' : 'Пишут музыку регулярно', $isEn ? ($regularCount . ' of ' . $regularAnswered . ' answered') : ('ежедневно или еженедельно · ' . $regularCount . ' из ' . $regularAnswered)],
        [$pct($lostIdeaCount, $lostAnswered), $isEn ? 'Lost ideas away from DAW' : 'Теряли идею без DAW', $isEn ? ('often/sometimes · ' . $lostIdeaCount . ' of ' . $lostAnswered) : ('часто или иногда · ' . $lostIdeaCount . ' из ' . $lostAnswered)],
        [$pct($paidAnyCount, $paidAnswered), $isEn ? 'Spend money on music tools' : 'Тратят на софт и звуки', $isEn ? ('spent >$0/yr · ' . $paidAnyCount . ' of ' . $paidAnswered) : ('траты > 0 за год · ' . $paidAnyCount . ' из ' . $paidAnswered)],
        [$pct($triedYes, $triedAnswered), $isEn ? 'Already tried MPS' : 'Уже пробовали MPS', $isEn ? ($triedYes . ' of ' . $triedAnswered . ' respondents') : ($triedYes . ' из ' . $triedAnswered . ' респондентов')],
        [$npsCount > 0 ? ($npsMean . ' / 10') : '—', $isEn ? 'MPS rating & NPS' : 'Оценка MPS и NPS', $npsCount > 0 ? ('NPS ' . $npsScore . ' · n=' . $npsCount) : ($isEn ? 'awaiting MPS trials' : 'ждём оценки от пробовавших')],
    ];
    if ($downloads !== null && $segment === 'all') {
        $kpis[0][2] = ($isEn ? 'APK downloads: ' : 'Скачиваний APK: ') . $downloads;
    }
    foreach ($kpis as [$big, $title, $sub]) {
        $out .= '<li><b>' . e($big) . '</b><strong>' . e($title) . '</strong><span>' . e($sub) . '</span></li>';
    }
    $out .= '</ul>';

    // Helper to build a single question card
    $renderQuestion = static function (string $id, string $mode = 'hbar', array $highlight = [], bool $keepOrder = false) use ($qById, $tally, $n, $lang, $segDefs, $segment, $isEn): string {
        if (!isset($qById[$id])) {
            return '';
        }
        $q = $qById[$id];
        $answered = $tally[$id]['answered'] ?? 0;
        if ($answered === 0) {
            return '';
        }
        $optsMap = $q['options'];
        if (!$keepOrder) {
            uksort($optsMap, static fn ($a, $b) => ($tally[$id]['options'][$b] ?? 0) <=> ($tally[$id]['options'][$a] ?? 0));
        }
        $maxC = max(1, ...array_map(static fn ($k) => $tally[$id]['options'][$k] ?? 0, array_keys($optsMap)));

        $rowsData = [];
        $slicesData = [];
        $table = '<details class="numbers"><summary>' . ($isEn ? 'Exact numbers' : 'Таблица чисел') . '</summary><table><tbody>';
        $idx = 0;
        foreach ($optsMap as $key => $labels) {
            $idx++;
            $c = $tally[$id]['options'][$key] ?? 0;
            $share = $c * 100.0 / $answered;
            $label = (string) ($labels[$lang] ?? $labels['ru']);
            $isHi = $highlight ? in_array($key, $highlight, true) : ($c > 0 && $c === $maxC);
            $rowsData[] = [
                'label' => $label,
                'count' => $c,
                'pct' => $share,
                'cls' => $isHi ? 'c1' : 'c-muted',
            ];
            $slicesData[] = [
                'label' => $label,
                'count' => $c,
                'pct' => $share,
                'cls' => 's' . min(6, $idx),
                'keyCls' => 'c' . min(6, $idx),
            ];
            $table .= '<tr><th scope="row">' . e($label) . '</th><td>' . $c . '</td><td>' . (int) round($share) . '%</td></tr>';
        }
        $table .= '</tbody></table></details>';

        $title = (string) ($q['q'][$lang] ?? $q['q']['ru']);
        $multiHint = $q['type'] === 'many' ? ($isEn ? ' · multiple choice (% of answered)' : ' · несколько вариантов (% от ответивших)') : '';
        $note = ($isEn ? 'Answered: ' : 'Ответили: ') . $answered . ($isEn ? ' of ' : ' из ') . $n . ' · ' . $segDefs[$segment] . $multiHint;
        $svg = $mode === 'donut'
            ? chart_donut_svg($slicesData, $answered, $isEn ? 'answers' : chart_plural($answered, 'ответ', 'ответа', 'ответов'), $title)
            : chart_hbar($rowsData, $title);

        return chart_card($title, $note, $svg . $table, [
            'export' => true,
            'slug' => 'mps-' . $id,
            'lang' => $lang,
        ]);
    };

    // ---- Section 1: Audience Profile
    $out .= '<h2 class="deck-h">' . e($isEn ? '1. Audience & Musical Workflow' : '1. Аудитория и ритм работы') . '</h2><div class="charts deck-grid">'
        . $renderQuestion('role', 'hbar', ['self', 'release', 'pro'], true)
        . $renderQuestion('where', 'donut', [], false)
        . $renderQuestion('freq', 'hbar', ['daily', 'weekly'], true)
        . $renderQuestion('genres', 'hbar', [], false)
        . '</div>';

    // ---- Section 2: Tools, Lost Ideas & Pain Points
    $out .= '<h2 class="deck-h">' . e($isEn ? '2. Tools, Pain Points & Sound Sources' : '2. Инструменты, боли и источники звуков') . '</h2><div class="charts deck-grid">'
        . $renderQuestion('tools', 'hbar', [], false)
        . $renderQuestion('lost', 'hbar', ['often', 'sometimes'], true)
        . $renderQuestion('pains', 'hbar', [], false)
        . $renderQuestion('sounds', 'hbar', [], false)
        . $renderQuestion('share', 'hbar', [], false)
        . '</div>';

    // ---- Section 3: Monetization & MPS Evaluation
    $sec3 = $renderQuestion('paid', 'hbar', ['low', 'mid', 'high'], true)
        . $renderQuestion('tried', 'donut', ['yes'], true);
    if ($npsCount > 0) {
        $npsTitle = (string) ($qById['nps']['q'][$lang] ?? 'NPS');
        $npsNote = ($isEn ? 'Answered: ' : 'Ответили: ') . $npsCount . ' · ' . ($isEn ? 'Mean ' : 'Средняя ') . $npsMean . '/10 · NPS ' . $npsScore;
        $npsTable = '<details class="numbers"><summary>' . ($isEn ? 'Exact numbers' : 'Таблица чисел') . '</summary><table><tbody>';
        for ($s = 0; $s <= 10; $s++) {
            $c = $npsCounts[$s];
            $npsTable .= '<tr><th scope="row">' . $s . '</th><td>' . $c . '</td><td>' . (int) round($c * 100 / $npsCount) . '%</td></tr>';
        }
        $npsTable .= '</tbody></table></details>';
        $sec3 .= chart_card($npsTitle, $npsNote, chart_nps_svg($npsCounts, $lang, $npsTitle) . $npsTable, [
            'export' => true,
            'slug' => 'mps-nps',
            'lang' => $lang,
            'wide' => true,
        ]);
    }
    if ($sec3 !== '') {
        $out .= '<h2 class="deck-h">' . e($isEn ? '3. Spending & MPS Product Rating' : '3. Готовность тратить и оценка MPS') . '</h2><div class="charts deck-grid">' . $sec3 . '</div>';
    }

    // ---- Section 4: Cross-Tabs (100% honest analytical breakdowns)
    $crossOut = '';
    // 4a. Lost ideas by primary workflow device (where x lost)
    if (isset($qById['where'], $qById['lost'])) {
        $crossWhere = [];
        foreach ($qById['where']['options'] as $wk => $wl) {
            $crossWhere[$wk] = ['label' => (string) ($wl[$lang] ?? $wl['ru']), 'total' => 0, 'counts' => ['often' => 0, 'sometimes' => 0, 'rarely' => 0, 'never' => 0]];
        }
        foreach ($rows as $a) {
            $w = $a['where'] ?? '';
            $l = $a['lost'] ?? '';
            if (isset($crossWhere[$w], $crossWhere[$w]['counts'][$l])) {
                $crossWhere[$w]['total']++;
                $crossWhere[$w]['counts'][$l]++;
            }
        }
        $lostSegments = [
            ['key' => 'often', 'label' => (string) $qById['lost']['options']['often'][$lang], 'cls' => 'c1'],
            ['key' => 'sometimes', 'label' => (string) $qById['lost']['options']['sometimes'][$lang], 'cls' => 'c4'],
            ['key' => 'rarely', 'label' => (string) $qById['lost']['options']['rarely'][$lang], 'cls' => 'c2'],
            ['key' => 'never', 'label' => (string) $qById['lost']['options']['never'][$lang], 'cls' => 'c-muted'],
        ];
        $svgCross1 = chart_stacked_svg(array_values($crossWhere), $lostSegments, $isEn ? 'Lost ideas by device' : 'Потеря идей по устройству');
        if ($svgCross1 !== '') {
            $crossOut .= chart_card(
                $isEn ? 'Lost ideas by device setup' : 'Как часто теряют идею в зависимости от устройства',
                $segDefs[$segment],
                $svgCross1,
                ['export' => true, 'slug' => 'mps-cross-where-lost', 'lang' => $lang]
            );
        }
    }

    // 4b. Spending by musical role (role x paid)
    if (isset($qById['role'], $qById['paid'])) {
        $crossRole = [];
        foreach ($qById['role']['options'] as $rk => $rl) {
            $crossRole[$rk] = ['label' => (string) ($rl[$lang] ?? $rl['ru']), 'total' => 0, 'counts' => ['high' => 0, 'mid' => 0, 'low' => 0, 'zero' => 0]];
        }
        foreach ($rows as $a) {
            $r = $a['role'] ?? '';
            $p = $a['paid'] ?? '';
            if (isset($crossRole[$r], $crossRole[$r]['counts'][$p])) {
                $crossRole[$r]['total']++;
                $crossRole[$r]['counts'][$p]++;
            }
        }
        $paidSegments = [
            ['key' => 'high', 'label' => $isEn ? 'Over $50' : 'Больше 3000 ₽', 'cls' => 'c1'],
            ['key' => 'mid', 'label' => $isEn ? '$10–50' : '500–3000 ₽', 'cls' => 'c3'],
            ['key' => 'low', 'label' => $isEn ? 'Up to $10' : 'До 500 ₽', 'cls' => 'c2'],
            ['key' => 'zero', 'label' => $isEn ? 'Nothing' : 'Ничего', 'cls' => 'c-muted'],
        ];
        $svgCross2 = chart_stacked_svg(array_values($crossRole), $paidSegments, $isEn ? 'Annual spending by role' : 'Траты на музыку по уровню опыта');
        if ($svgCross2 !== '') {
            $crossOut .= chart_card(
                $isEn ? 'Music spending by experience level' : 'Годовые траты на софт и звуки по уровню в музыке',
                $segDefs[$segment],
                $svgCross2,
                ['export' => true, 'slug' => 'mps-cross-role-paid', 'lang' => $lang]
            );
        }
    }

    if ($crossOut !== '') {
        $out .= '<h2 class="deck-h">' . e($isEn ? '4. Cross-Tab Insights' : '4. Срезы и взаимосвязи') . '</h2><div class="charts deck-grid">' . $crossOut . '</div>';
    }

    $out .= '</div>';
    return $out;
}

/**
 * Operational / time-series statistics tab in /admin/?tab=stats.
 *
 * @param array<string, string> $kinds
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
    $shift = (int) $zone->getOffset($now);
    $local = 'DATE(DATE_ADD(created_at, INTERVAL ' . $shift . ' SECOND))';

    $buckets = [];
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
        ['Скачивания', $perDay('mps_events', 'download'), 1, ['скачивание', 'скачивания', 'скачиваний'], 'downloads'],
        ['Анкеты', $perDay('mps_responses', 'survey'), 2, ['анкета', 'анкеты', 'анкет'], 'surveys'],
        ['Отзывы', $perDay('mps_responses', 'feedback'), 3, ['отзыв', 'отзыва', 'отзывов'], 'feedback'],
    ];
    $periods = [7 => '7 дней', 30 => '30 дней', 90 => '90 дней', 365 => 'Год'];
    $out = '<nav class="period" aria-label="Период">';
    foreach ($periods as $value => $name) {
        $out .= '<a href="/admin/?tab=stats&amp;days=' . $value . '"' . ($value === $days ? ' aria-current="true"' : '') . '>' . $name . '</a>';
    }
    $out .= '</nav><h2>Что происходило ' . $unit . '</h2><div class="charts">';
    foreach ($series as [$title, $values, $colour, $words, $slug]) {
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
        $out .= chart_card($title, $note, chart_bars($points, $colour, $about) . $table, ['export' => true, 'slug' => 'mps-time-' . $slug]);
    }

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
        $out .= chart_card(
            'Всего с начала',
            'с ' . $from->format('j') . ' ' . CHART_MONTHS[(int) $from->format('n') - 1] . ' ' . $from->format('Y'),
            chart_lines($lines, $labels, 'Итоги нарастающим числом: ' . implode(', ', array_map(static fn ($n, $v) => $n . ' — ' . $v, array_keys($running), $running))) . $legend,
            ['export' => true, 'slug' => 'mps-cumulative']
        );
    }
    $out .= '</div>';

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
