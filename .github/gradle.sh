#!/usr/bin/env bash
# Runs ./gradlew with the given tasks. On failure it also writes the real Gradle error as a GitHub annotation,
# so the cause is visible on the run page and in build-error.txt.
set +e
./gradlew "$@" --stacktrace --console=plain 2>&1 | tee gradle.log
code=${PIPESTATUS[0]}
if [ "$code" -ne 0 ]; then
  msg=$(grep -v -E '^[[:space:]]+at |^[[:space:]]*\.\.\. [0-9]+ more' gradle.log \
        | grep -A22 -E 'What went wrong|^e: |Caused by|Could not' | head -50 \
        | sed -e 's/%/%25/g' -e 's/\r//g' | awk '{printf "%s%%0A", $0}')
  echo "::error title=Gradle failed::${msg:0:3500}"
fi
exit "$code"
