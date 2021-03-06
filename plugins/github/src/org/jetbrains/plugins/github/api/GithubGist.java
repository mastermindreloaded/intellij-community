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

import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

import static org.jetbrains.plugins.github.api.GithubGistRaw.GistFileRaw;

/**
 * @author Aleksey Pivovarov
 */
@SuppressWarnings("UnusedDeclaration")
public class GithubGist {
  @NotNull private String id;
  @NotNull private String description;

  private boolean isPublic;

  @NotNull private String url;
  @NotNull private String htmlUrl;

  @NotNull private Map<String, GistFile> files;

  @Nullable private GithubUser user;

  public static class GistFile {
    @NotNull private Long size;
    @NotNull private String filename;
    @NotNull private String content;

    @NotNull private String raw_url;

    @NotNull private String type;

    @NotNull
    public static GistFile create(@Nullable GistFileRaw raw) throws JsonException {
      try {
        if (raw == null) throw new JsonException("raw is null");
        if (raw.size == null) throw new JsonException("size is null");
        if (raw.filename == null) throw new JsonException("filename is null");
        if (raw.content == null) throw new JsonException("content is null");
        if (raw.raw_url == null) throw new JsonException("raw_url is null");
        if (raw.type == null) throw new JsonException("type is null");

        return new GistFile(raw.size, raw.filename, raw.content, raw.raw_url, raw.type);
      }
      catch (JsonException e) {
        throw new JsonException("GistFile parse error", e);
      }
    }

    private GistFile(@NotNull Long size, @NotNull String filename,
                       @NotNull String content,
                       @NotNull String raw_url,
                       @NotNull String type) {
      this.size = size;
      this.filename = filename;
      this.content = content;
      this.raw_url = raw_url;
      this.type = type;
    }

    @NotNull
    public Long getSize() {
      return size;
    }

    @NotNull
    public String getFilename() {
      return filename;
    }

    @NotNull
    public String getContent() {
      return content;
    }

    @NotNull
    public String getRaw_url() {
      return raw_url;
    }

    @NotNull
    public String getType() {
      return type;
    }
  }

  @NotNull
  public Map<String, String> getContent() {
    Map<String, String> ret = new HashMap<String, String>();
    for (Map.Entry<String, GistFile> file : getFiles().entrySet()) {
      ret.put(file.getKey(), file.getValue().getContent());
    }
    return ret;
  }

  @NotNull
  public static GithubGist create(@Nullable GithubGistRaw raw) throws JsonException {
    try {
      if (raw == null) throw new JsonException("raw is null");
      if (raw.id == null) throw new JsonException("id is null");
      if (raw.description == null) throw new JsonException("description is null");
      if (raw.isPublic == null) throw new JsonException("isPublic is null");
      if (raw.url == null) throw new JsonException("url is null");
      if (raw.htmlUrl == null) throw new JsonException("htmlUrl is null");
      if (raw.files == null) throw new JsonException("files is null");

      Map<String, GistFile> files = new HashMap<String, GistFile>();
      for (Map.Entry<String, GistFileRaw> entry : raw.files.entrySet()) {
        files.put(entry.getKey(), GistFile.create(entry.getValue()));
      }
      GithubUser user = raw.user == null ? null : GithubUser.create(raw.user);

      return new GithubGist(raw.id, raw.description, raw.isPublic, raw.url, raw.htmlUrl, files, user);
    }
    catch (JsonException e) {
      throw new JsonException("GithubGist parse error", e);
    }
  }


  private GithubGist(@NotNull String id, @NotNull String description,
                       boolean isPublic,
                       @NotNull String url,
                       @NotNull String htmlUrl,
                       @NotNull Map<String, GistFile> files,
                       @Nullable GithubUser user) {
    this.id = id;
    this.description = description;
    this.isPublic = isPublic;
    this.url = url;
    this.htmlUrl = htmlUrl;
    this.files = files;
    this.user = user;
  }

  @NotNull
  public String getId() {
    return id;
  }

  @NotNull
  public String getDescription() {
    return description;
  }

  public boolean isPublic() {
    return isPublic;
  }

  @NotNull
  public String getUrl() {
    return url;
  }

  @NotNull
  public String getHtmlUrl() {
    return htmlUrl;
  }

  @NotNull
  public Map<String, GistFile> getFiles() {
    return files;
  }

  @Nullable
  public GithubUser getUser() {
    return user;
  }
}
