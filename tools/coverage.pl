#!/usr/bin/env perl
# Line coverage of the JVM unit tests and the on-device tests together, by package and in total (the
# total is the figure in README.org → Tests). A line counts as covered when either run covers it.
#
#   ./gradlew -Pcoverage :app:createDebugUnitTestCoverageReport :app:createDebugAndroidTestCoverageReport
#   perl tools/coverage.pl [<tag>]
#
# With <tag> (the previous release), it also prints how many of the lines added since then are covered.
use strict;
use warnings;

my $since = shift;
my @reports = (
    'app/build/reports/coverage/test/debug/report.xml',
    'app/build/reports/coverage/androidTest/debug/connected/report.xml',
);

my (%covered, %package);
for my $report (@reports) {
    open my $in, '<', $report or die "$report: $! (run the Gradle line above first)\n";
    my $xml = do { local $/; <$in> };
    while ($xml =~ m{<package name="([^"]+)">(.*?)</package>}sg) {
        my ($pkg, $body) = ($1, $2);
        while ($body =~ m{<sourcefile name="([^"]+)">(.*?)</sourcefile>}sg) {
            my ($file, $lines) = ("$pkg/$1", $2);
            while ($lines =~ m{<line nr="(\d+)" mi="\d+" ci="(\d+)"}g) {
                my $key = "$file:$1";
                $package{$key} = $pkg;
                $covered{$key} ||= $2 > 0 ? 1 : 0;
            }
        }
    }
}

my (%total, %hit);
for my $key (keys %covered) {
    (my $pkg = $package{$key}) =~ s{^com/mtwabpin/kolahal/?}{};
    $pkg = $pkg eq '' ? '(root)' : "=$pkg/=";
    $total{$pkg}++;
    $hit{$pkg} += $covered{$key};
}
my ($lines, $hits) = (0, 0);
for my $pkg (sort { $total{$b} <=> $total{$a} } keys %total) {
    printf "| %-16s | %5d | %6.1f%% |\n", $pkg, $total{$pkg}, 100 * $hit{$pkg} / $total{$pkg};
    $lines += $total{$pkg};
    $hits += $hit{$pkg};
}
printf "| %-16s | %5d | %6.1f%% |\n", "*All*", $lines, 100 * $hits / $lines;

exit unless $since;
my ($file, $new, $newHit) = ('', 0, 0);
for (`git diff -U0 $since..HEAD -- app/src/main/java`) {
    if (m{^\+\+\+ b/app/src/main/java/(\S+)}) { $file = $1; next }
    next unless /^@@ -\S+ \+(\d+)(?:,(\d+))? @@/;
    for my $nr ($1 .. $1 + (defined $2 ? $2 : 1) - 1) {
        next unless exists $covered{"$file:$nr"};
        $new++;
        $newHit += $covered{"$file:$nr"};
    }
}
printf "Lines added since %s: %d, covered %.1f%%\n", $since, $new, $new ? 100 * $newHit / $new : 0;
