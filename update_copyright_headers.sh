#!/bin/sh
#
# Copyright 2023 Tomorrow GmbH @ https://tomorrow.one
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#          http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

# Shell script that can be used to update copyright headers
# for files that have been updated during this year
# determined by the git diff command.

year=$(date +'%Y')

echo "Updating copyright headers for all java files that have been changed in $year"
# Changed in the specified year and committed
for file in $(git log --pretty='%aI %H' \
    |awk -v year="$year" '$1 >= year"-01-01" && $1 <= year"-12-31" { print $2 }' | git --no-pager log --no-walk --name-only --stdin | grep -E "^.+\.(java)$"| sort | uniq ); do
  echo "Updating file $file"
	sed -i "" "s/Copyright \([0-9]\{4\}\)\(-[0-9]\{4\}\)\{0,1\} Tomorrow GmbH/Copyright \1-$year Tomorrow GmbH/g" "$file";
done
