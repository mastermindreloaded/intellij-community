/*
 * Copyright 2000-2012 JetBrains s.r.o.
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
package com.intellij.openapi.roots.impl.libraries;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentSerializationUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModifiableRootModel;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.PersistentOrderRootType;
import com.intellij.openapi.roots.RootProvider;
import com.intellij.openapi.roots.impl.RootModelImpl;
import com.intellij.openapi.roots.impl.RootProviderBaseImpl;
import com.intellij.openapi.roots.libraries.*;
import com.intellij.openapi.util.*;
import com.intellij.openapi.vfs.StandardFileSystems;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileVisitor;
import com.intellij.openapi.vfs.pointers.VirtualFilePointer;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerContainer;
import com.intellij.openapi.vfs.pointers.VirtualFilePointerManager;
import com.intellij.util.ArrayUtil;
import com.intellij.util.SmartList;
import com.intellij.util.containers.HashMap;
import com.intellij.util.xmlb.SkipDefaultValuesSerializationFilters;
import com.intellij.util.xmlb.XmlSerializer;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.intellij.openapi.vfs.VirtualFileVisitor.ONE_LEVEL_DEEP;
import static com.intellij.openapi.vfs.VirtualFileVisitor.SKIP_ROOT;

/**
 * @author dsl
 */
public class LibraryImpl extends TraceableDisposable implements LibraryEx.ModifiableModelEx, LibraryEx {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.roots.impl.impl.LibraryImpl");
  @NonNls public static final String LIBRARY_NAME_ATTR = "name";
  @NonNls public static final String LIBRARY_TYPE_ATTR = "type";
  @NonNls public static final String ROOT_PATH_ELEMENT = "root";
  @NonNls public static final String ELEMENT = "library";
  @NonNls public static final String PROPERTIES_ELEMENT = "properties";
  private static final SkipDefaultValuesSerializationFilters SERIALIZATION_FILTERS = new SkipDefaultValuesSerializationFilters();
  private String myName;
  private final LibraryTable myLibraryTable;
  private final Map<OrderRootType, VirtualFilePointerContainer> myRoots;
  private final JarDirectories myJarDirectories = new JarDirectories();
  private final LibraryImpl mySource;
  private PersistentLibraryKind<?> myKind;
  private LibraryProperties myProperties;

  private final MyRootProviderImpl myRootProvider = new MyRootProviderImpl();
  private final ModifiableRootModel myRootModel;
  private boolean myDisposed;
  private final Disposable myPointersDisposable = Disposer.newDisposable();
  private final JarDirectoryWatcher myRootsWatcher = JarDirectoryWatcherFactory.getInstance().createWatcher(myJarDirectories, myRootProvider);

  LibraryImpl(LibraryTable table, Element element, ModifiableRootModel rootModel) throws InvalidDataException {
    this(table, rootModel, null, element.getAttributeValue(LIBRARY_NAME_ATTR),
         (PersistentLibraryKind<?>)LibraryKind.findById(element.getAttributeValue(LIBRARY_TYPE_ATTR)));
    readProperties(element);
    myJarDirectories.readExternal(element);
    readRoots(element);
    myRootsWatcher.updateWatchedRoots();
  }

  LibraryImpl(String name, @Nullable final PersistentLibraryKind<?> kind, LibraryTable table, ModifiableRootModel rootModel) {
    this(table, rootModel, null, name, kind);
    if (kind != null) {
      myProperties = kind.createDefaultProperties();
    }
  }

  private LibraryImpl(@NotNull LibraryImpl from, LibraryImpl newSource, ModifiableRootModel rootModel) {
    this(from.myLibraryTable, rootModel, newSource, from.myName, from.myKind);
    assert !from.isDisposed();
    if (from.myKind != null && from.myProperties != null) {
      myProperties = myKind.createDefaultProperties();
      //noinspection unchecked
      myProperties.loadState(from.myProperties.getState());
    }
    for (OrderRootType rootType : getAllRootTypes()) {
      final VirtualFilePointerContainer thisContainer = myRoots.get(rootType);
      final VirtualFilePointerContainer thatContainer = from.myRoots.get(rootType);
      thisContainer.addAll(thatContainer);
    }
    myJarDirectories.copyFrom(from.myJarDirectories);
  }

  // primary
  private LibraryImpl(LibraryTable table, ModifiableRootModel rootModel, LibraryImpl newSource, String name, @Nullable final PersistentLibraryKind<?> kind) {
    super(new Throwable());
    myLibraryTable = table;
    myRootModel = rootModel;
    mySource = newSource;
    myKind = kind;
    myName = name;
    //init roots depends on my myKind
    myRoots = initRoots();
    Disposer.register(this, myRootsWatcher);
  }

  private Set<OrderRootType> getAllRootTypes() {
    Set<OrderRootType> rootTypes = new HashSet<OrderRootType>();
    rootTypes.addAll(Arrays.asList(OrderRootType.getAllTypes()));
    if (myKind != null) {
      rootTypes.addAll(Arrays.asList(myKind.getAdditionalRootTypes()));
    }
    return rootTypes;
  }

  @Override
  public void dispose() {
    if (isDisposed()) {
      throwDisposalError("Already disposed:");
    }
    myDisposed = true;
    kill(null);
  }

  @Override
  public boolean isDisposed() {
    return myDisposed;
  }

  @Override
  public String getName() {
    return myName;
  }

  @Override
  @NotNull
  public String[] getUrls(@NotNull OrderRootType rootType) {
    assert !isDisposed();
    final VirtualFilePointerContainer result = myRoots.get(rootType);
    return result.getUrls();
  }

  @Override
  @NotNull
  public VirtualFile[] getFiles(@NotNull OrderRootType rootType) {
    assert !isDisposed();
    final List<VirtualFile> expanded = new ArrayList<VirtualFile>();
    for (VirtualFile file : myRoots.get(rootType).getFiles()) {
      if (file.isDirectory()) {
        if (myJarDirectories.contains(rootType, file.getUrl())) {
          collectJarFiles(file, expanded, myJarDirectories.isRecursive(rootType, file.getUrl()));
          continue;
        }
      }
      expanded.add(file);
    }
    return VfsUtilCore.toVirtualFileArray(expanded);
  }

  public static void collectJarFiles(final VirtualFile dir, final List<VirtualFile> container, final boolean recursively) {
    VfsUtilCore.visitChildrenRecursively(dir, new VirtualFileVisitor(SKIP_ROOT, (recursively ? null : ONE_LEVEL_DEEP)) {
      @Override
      public boolean visitFile(@NotNull VirtualFile file) {
        final VirtualFile jarRoot = StandardFileSystems.getJarRootForLocalFile(file);
        if (jarRoot != null) {
          container.add(jarRoot);
          return false;
        }
        return true;
      }
    });
  }

  @Override
  public void setName(String name) {
    LOG.assertTrue(isWritable());
    myName = name;
  }

  /* you have to commit modifiable model or dispose it by yourself! */
  @Override
  @NotNull
  public ModifiableModel getModifiableModel() {
    assert !isDisposed();
    return new LibraryImpl(this, this, myRootModel);
  }

  @Override
  public Library cloneLibrary(RootModelImpl rootModel) {
    LOG.assertTrue(myLibraryTable == null);
    final LibraryImpl clone = new LibraryImpl(this, null, rootModel);
    clone.myRootsWatcher.updateWatchedRoots();
    return clone;
  }

  @Override
  public List<String> getInvalidRootUrls(OrderRootType type) {
    if (myDisposed) return Collections.emptyList();

    final List<VirtualFilePointer> pointers = myRoots.get(type).getList();
    List<String> invalidPaths = null;
    for (VirtualFilePointer pointer : pointers) {
      if (!pointer.isValid()) {
        if (invalidPaths == null) {
          invalidPaths = new SmartList<String>();
        }
        invalidPaths.add(pointer.getUrl());
      }
    }
    return invalidPaths == null ? Collections.<String>emptyList() : invalidPaths;
  }

  @Override
  public void setProperties(LibraryProperties properties) {
    LOG.assertTrue(isWritable());
    myProperties = properties;
  }

  @Override
  @NotNull
  public RootProvider getRootProvider() {
    return myRootProvider;
  }

  private Map<OrderRootType, VirtualFilePointerContainer> initRoots() {
    Disposer.register(this, myPointersDisposable);

    Map<OrderRootType, VirtualFilePointerContainer> result = new HashMap<OrderRootType, VirtualFilePointerContainer>(4);

    for (OrderRootType rootType : getAllRootTypes()) {
      result.put(rootType, VirtualFilePointerManager.getInstance().createContainer(myPointersDisposable));
    }

    return result;
  }

  @Override
  public void readExternal(Element element) throws InvalidDataException {
    readName(element);
    readProperties(element);
    readRoots(element);
    myJarDirectories.readExternal(element);
    myRootsWatcher.updateWatchedRoots();
  }

  private void readProperties(Element element) {
    final String typeId = element.getAttributeValue(LIBRARY_TYPE_ATTR);
    if (typeId == null) return;

    myKind = (PersistentLibraryKind<?>) LibraryKind.findById(typeId);
    if (myKind == null) return;

    myProperties = myKind.createDefaultProperties();
    final Element propertiesElement = element.getChild(PROPERTIES_ELEMENT);
    if (propertiesElement != null) {
      final Class<?> stateClass = ComponentSerializationUtil.getStateClass(myProperties.getClass());
      //noinspection unchecked
      myProperties.loadState(XmlSerializer.deserialize(propertiesElement, stateClass));
    }
  }

  private void readName(Element element) {
    myName = element.getAttributeValue(LIBRARY_NAME_ATTR);
  }

  private void readRoots(Element element) throws InvalidDataException {
    for (OrderRootType rootType : getAllRootTypes()) {
      final Element rootChild = element.getChild(rootType.name());
      if (rootChild == null) {
        continue;
      }
      VirtualFilePointerContainer roots = myRoots.get(rootType);
      roots.readExternal(rootChild, ROOT_PATH_ELEMENT);
    }
  }

  //TODO<rv> Remove the next two methods as a temporary solution. Sort in OrderRootType.
  //
  public static List<OrderRootType> sortRootTypes(Collection<OrderRootType> rootTypes) {
    List<OrderRootType> allTypes = new ArrayList<OrderRootType>(rootTypes);
    Collections.sort(allTypes, new Comparator<OrderRootType>() {
      @Override
      public int compare(final OrderRootType o1, final OrderRootType o2) {
        return getSortKey(o1).compareTo(getSortKey(o2));
      }
    });
    return allTypes;
  }

  private static String getSortKey(OrderRootType orderRootType) {
    if (orderRootType instanceof PersistentOrderRootType) {
      return ((PersistentOrderRootType)orderRootType).getSdkRootName();
    }
    if (orderRootType instanceof OrderRootType.DocumentationRootType) {
      return ((OrderRootType.DocumentationRootType)orderRootType).getSdkRootName();
    }
    return "";
  }

  @Override
  public void writeExternal(Element rootElement) throws WriteExternalException {
    LOG.assertTrue(!isDisposed(), "Already disposed!");

    Element element = new Element(ELEMENT);
    if (myName != null) {
      element.setAttribute(LIBRARY_NAME_ATTR, myName);
    }
    if (myKind != null) {
      element.setAttribute(LIBRARY_TYPE_ATTR, myKind.getKindId());
      final Object state = myProperties.getState();
      if (state != null) {
        final Element propertiesElement = XmlSerializer.serialize(state, SERIALIZATION_FILTERS);
        if (propertiesElement != null && (!propertiesElement.getContent().isEmpty() || !propertiesElement.getAttributes().isEmpty())) {
          element.addContent(propertiesElement.setName(PROPERTIES_ELEMENT));
        }
      }
    }
    ArrayList<OrderRootType> storableRootTypes = new ArrayList<OrderRootType>();
    storableRootTypes.addAll(Arrays.asList(OrderRootType.getAllTypes()));
    if (myKind != null) {
      storableRootTypes.addAll(Arrays.asList(myKind.getAdditionalRootTypes()));
    }
    for (OrderRootType rootType : sortRootTypes(storableRootTypes)) {
      final VirtualFilePointerContainer roots = myRoots.get(rootType);
      if (roots.size() == 0 && rootType.skipWriteIfEmpty()) continue; //compatibility iml/ipr
      final Element rootTypeElement = new Element(rootType.name());
      roots.writeExternal(rootTypeElement, ROOT_PATH_ELEMENT);
      element.addContent(rootTypeElement);
    }
    myJarDirectories.writeExternal(element);
    rootElement.addContent(element);
  }

  private boolean isWritable() {
    return mySource != null;
  }

  @Nullable
  @Override
  public PersistentLibraryKind<?> getKind() {
    return myKind;
  }

  @Override
  public LibraryProperties getProperties() {
    return myProperties;
  }

  @Override
  public void setKind(PersistentLibraryKind<?> kind) {
    LOG.assertTrue(isWritable());
    LOG.assertTrue(myKind == null || myKind == kind, "Library kind cannot be changed from " + myKind + " to " + kind);
    myKind = kind;
  }

  @Override
  public void addRoot(@NotNull String url, @NotNull OrderRootType rootType) {
    LOG.assertTrue(isWritable());
    assert !isDisposed();

    final VirtualFilePointerContainer container = myRoots.get(rootType);
    container.add(url);
  }

  @Override
  public void addRoot(@NotNull VirtualFile file, @NotNull OrderRootType rootType) {
    LOG.assertTrue(isWritable());
    assert !isDisposed();

    final VirtualFilePointerContainer container = myRoots.get(rootType);
    container.add(file);
  }

  @Override
  public void addJarDirectory(@NotNull final String url, final boolean recursive) {
    addJarDirectory(url, recursive, JarDirectories.DEFAULT_JAR_DIRECTORY_TYPE);
  }

  @Override
  public void addJarDirectory(@NotNull final VirtualFile file, final boolean recursive) {
    addJarDirectory(file, recursive, JarDirectories.DEFAULT_JAR_DIRECTORY_TYPE);
  }

  @Override
  public void addJarDirectory(@NotNull final String url, final boolean recursive, @NotNull OrderRootType rootType) {
    assert !isDisposed();
    LOG.assertTrue(isWritable());
    final VirtualFilePointerContainer container = myRoots.get(rootType);
    container.add(url);
    myJarDirectories.add(rootType, url, recursive);
  }

  @Override
  public void addJarDirectory(@NotNull final VirtualFile file, final boolean recursive, @NotNull OrderRootType rootType) {
    assert !isDisposed();
    LOG.assertTrue(isWritable());
    final VirtualFilePointerContainer container = myRoots.get(rootType);
    container.add(file);
    myJarDirectories.add(rootType, file.getUrl(), recursive);
  }

  @Override
  public boolean isJarDirectory(@NotNull final String url) {
    return isJarDirectory(url, JarDirectories.DEFAULT_JAR_DIRECTORY_TYPE);
  }

  @Override
  public boolean isJarDirectory(@NotNull final String url, @NotNull final OrderRootType rootType) {
    return myJarDirectories.contains(rootType, url);
  }

  @Override
  public boolean isValid(@NotNull final String url, @NotNull final OrderRootType rootType) {
    final VirtualFilePointerContainer container = myRoots.get(rootType);
    final VirtualFilePointer fp = container.findByUrl(url);
    return fp != null && fp.isValid();
  }

  @Override
  public boolean removeRoot(@NotNull String url, @NotNull OrderRootType rootType) {
    assert !isDisposed();
    LOG.assertTrue(isWritable());
    final VirtualFilePointerContainer container = myRoots.get(rootType);
    final VirtualFilePointer byUrl = container.findByUrl(url);
    if (byUrl != null) {
      container.remove(byUrl);
      myJarDirectories.remove(rootType, url);
      return true;
    }
    return false;
  }

  @Override
  public void moveRootUp(@NotNull String url, @NotNull OrderRootType rootType) {
    assert !isDisposed();
    LOG.assertTrue(isWritable());
    final VirtualFilePointerContainer container = myRoots.get(rootType);
    container.moveUp(url);
  }

  @Override
  public void moveRootDown(@NotNull String url, @NotNull OrderRootType rootType) {
    assert !isDisposed();
    LOG.assertTrue(isWritable());
    final VirtualFilePointerContainer container = myRoots.get(rootType);
    container.moveDown(url);
  }

  @Override
  public boolean isChanged() {
    return !mySource.equals(this);
  }

  private boolean areRootsChanged(final LibraryImpl that) {
    return !that.equals(this);
    //final OrderRootType[] allTypes = OrderRootType.getAllTypes();
    //for (OrderRootType type : allTypes) {
    //  final String[] urls = getUrls(type);
    //  final String[] thatUrls = that.getUrls(type);
    //  if (urls.length != thatUrls.length) {
    //    return true;
    //  }
    //  for (int idx = 0; idx < urls.length; idx++) {
    //    final String url = urls[idx];
    //    final String thatUrl = thatUrls[idx];
    //    if (!Comparing.equal(url, thatUrl)) {
    //      return true;
    //    }
    //    final Boolean jarDirRecursive = myJarDirectories.get(url);
    //    final Boolean sourceJarDirRecursive = that.myJarDirectories.get(thatUrl);
    //    if (jarDirRecursive == null ? sourceJarDirRecursive != null : !jarDirRecursive.equals(sourceJarDirRecursive)) {
    //      return true;
    //    }
    //  }
    //}
    //return false;
  }

  public Library getSource() {
    return mySource;
  }

  @Override
  public void commit() {
    assert !isDisposed();
    mySource.commit(this);
    Disposer.dispose(this);
  }

  private void commit(@NotNull LibraryImpl fromModel) {
    if (myLibraryTable != null) {
      ApplicationManager.getApplication().assertWriteAccessAllowed();
    }
    if (!Comparing.equal(fromModel.myName, myName)) {
      myName = fromModel.myName;
      if (myLibraryTable instanceof LibraryTableBase) {
        ((LibraryTableBase)myLibraryTable).fireLibraryRenamed(this);
      }
    }
    myKind = fromModel.getKind();
    myProperties = fromModel.myProperties;
    if (areRootsChanged(fromModel)) {
      disposeMyPointers();
      copyRootsFrom(fromModel);
      myJarDirectories.copyFrom(fromModel.myJarDirectories);
      myRootsWatcher.updateWatchedRoots();
      myRootProvider.fireRootSetChanged();
    }
  }

  private void copyRootsFrom(LibraryImpl fromModel) {
    myRoots.clear();
    for (Map.Entry<OrderRootType, VirtualFilePointerContainer> entry : fromModel.myRoots.entrySet()) {
      OrderRootType rootType = entry.getKey();
      VirtualFilePointerContainer container = entry.getValue();
      VirtualFilePointerContainer clone = container.clone(myPointersDisposable);
      myRoots.put(rootType, clone);
    }
  }

  private void disposeMyPointers() {
    for (VirtualFilePointerContainer container : new THashSet<VirtualFilePointerContainer>(myRoots.values())) {
      container.killAll();
    }
    Disposer.dispose(myPointersDisposable);
    Disposer.register(this, myPointersDisposable);
  }

  private class MyRootProviderImpl extends RootProviderBaseImpl {
    @Override
    @NotNull
    public String[] getUrls(@NotNull OrderRootType rootType) {
      Set<String> originalUrls = new LinkedHashSet<String>(Arrays.asList(LibraryImpl.this.getUrls(rootType)));
      for (VirtualFile file : getFiles(rootType)) { // Add those expanded with jar directories.
        originalUrls.add(file.getUrl());
      }
      return ArrayUtil.toStringArray(originalUrls);
    }

    @Override
    @NotNull
    public VirtualFile[] getFiles(@NotNull final OrderRootType rootType) {
      return LibraryImpl.this.getFiles(rootType);
    }
  }

  @Override
  public LibraryTable getTable() {
    return myLibraryTable;
  }

  public boolean equals(final Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    final LibraryImpl library = (LibraryImpl)o;

    if (!myJarDirectories.equals(library.myJarDirectories)) return false;
    if (myName != null ? !myName.equals(library.myName) : library.myName != null) return false;
    if (myRoots != null ? !myRoots.equals(library.myRoots) : library.myRoots != null) return false;
    if (myKind != null ? !myKind.equals(library.myKind) : library.myKind != null) return false;
    if (myProperties != null ? !myProperties.equals(library.myProperties) : library.myProperties != null) return false;

    return true;
  }

  public int hashCode() {
    int result = myName != null ? myName.hashCode() : 0;
    result = 31 * result + (myRoots != null ? myRoots.hashCode() : 0);
    result = 31 * result + (myJarDirectories != null ? myJarDirectories.hashCode() : 0);
    return result;
  }

  @NonNls
  @Override
  public String toString() {
    return "Library: name:" + myName + "; jars:" + myJarDirectories + "; roots:" + myRoots.values();
  }

  @Nullable("will return non-null value only for module level libraries")
  public Module getModule() {
    return myRootModel == null ? null : myRootModel.getModule();
  }
}
