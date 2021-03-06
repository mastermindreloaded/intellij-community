/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package org.jetbrains.plugins.github.api;

import com.google.gson.annotations.SerializedName;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

/**
 * @author Aleksey Pivovarov
 */
@SuppressWarnings({"FieldCanBeLocal", "UnusedDeclaration"})
class GithubGistRequest {
  @NotNull private String description;
  @NotNull private Map<String, GistFile> files;

  @SerializedName("public")
  private boolean isPublic;

  public static class GistFile {
    @NotNull private String content;

    public GistFile(@NotNull String content) {
      this.content = content;
    }
  }

  public GithubGistRequest(@NotNull Map<String, String> files, @NotNull String description, boolean isPublic) {
    this.description = description;
    this.isPublic = isPublic;

    this.files = new HashMap<String, GistFile>();
    for (Map.Entry<String, String> file : files.entrySet()) {
      this.files.put(file.getKey(), new GistFile(file.getValue()));
    }
  }
}
