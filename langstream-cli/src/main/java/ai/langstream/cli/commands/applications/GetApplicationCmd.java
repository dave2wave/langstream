/*
 * Copyright DataStax, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.langstream.cli.commands.applications;

import lombok.SneakyThrows;
import picocli.CommandLine;

@CommandLine.Command(name = "get", header = "Get LangStream application status")
public class GetApplicationCmd extends BaseApplicationCmd {

    @CommandLine.Parameters(description = "ID of the application")
    private String applicationId;

    @CommandLine.Option(
            names = {"-o"},
            description = "Output format")
    private Formats format = Formats.raw;

    @CommandLine.Option(
            names = {"-s", "--stats"},
            description = "Include detailed information about the application and metrics")
    private boolean stats = false;

    @Override
    @SneakyThrows
    public void run() {
        final String body = getClient().applications().get(applicationId, stats);
        print(
                format,
                body,
                ListApplicationCmd.COLUMNS_FOR_RAW,
                ListApplicationCmd.getRawFormatValuesSupplier());
    }
}
