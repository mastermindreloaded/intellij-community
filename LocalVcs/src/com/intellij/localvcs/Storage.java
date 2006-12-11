package com.intellij.localvcs;

import java.io.*;

public class Storage {
  private File myDir;

  public Storage(File dir) {
    myDir = dir;
  }

  public ChangeList loadChangeList() {
    return load("list", new ChangeList(), new Loader<ChangeList>() {
      public ChangeList load(Stream s) throws IOException {
        return s.readChangeList();
      }
    });
  }

  public void storeChangeList(final ChangeList c) {
    store("list", new Storer() {
      public void store(Stream s) throws IOException {
        s.writeChangeList(c);
      }
    });
  }

  public RootEntry loadRootEntry() {
    return load("root", new RootEntry(), new Loader<RootEntry>() {
      public RootEntry load(Stream s) throws IOException {
        return (RootEntry)s.readEntry(); // todo cast!!!
      }
    });
  }

  public void storeRootEntry(final RootEntry e) {
    store("root", new Storer() {
      public void store(Stream s) throws IOException {
        s.writeEntry(e);
      }
    });
  }

  public Integer loadCounter() {
    return load("counter", 0, new Loader<Integer>() {
      public Integer load(Stream s) throws IOException {
        return s.readInteger();
      }
    });
  }

  public void storeCounter(final Integer i) {
    store("counter", new Storer() {
      public void store(Stream s) throws IOException {
        s.writeInteger(i);
      }
    });
  }

  private <T> T load(String fileName, T def, Loader<T> loader) {
    File f = new File(myDir, fileName);
    if (!f.exists()) return def;

    try {
      InputStream fs = new BufferedInputStream(new FileInputStream(f));
      try {
        return loader.load(new Stream(fs));
      }
      finally {
        fs.close();
      }
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void store(String fileName, Storer storer) {
    assureDirExists();
    File f = new File(myDir, fileName);
    try {
      f.createNewFile();
      OutputStream fs = new BufferedOutputStream(new FileOutputStream(f));
      try {
        storer.store(new Stream(fs));
      }
      finally {
        fs.close();
      }
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void assureDirExists() {
    if (!myDir.exists()) myDir.mkdirs();
  }

  private static interface Loader<T> {
    T load(Stream s) throws IOException;
  }

  private static interface Storer {
    void store(Stream s) throws IOException;
  }
}
