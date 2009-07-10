package com.intellij.slicer;

import com.intellij.ide.IdeBundle;
import com.intellij.ide.util.treeView.AbstractTreeNode;
import com.intellij.ide.actions.CloseTabToolbarAction;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Splitter;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiElement;
import com.intellij.ui.AutoScrollToSourceHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.TreeToolTipHandler;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.usageView.UsageInfo;
import com.intellij.usageView.UsageViewBundle;
import com.intellij.usages.Usage;
import com.intellij.usages.UsageViewSettings;
import com.intellij.usages.impl.UsagePreviewPanel;
import com.intellij.util.EditSourceOnDoubleClickHandler;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import gnu.trove.THashMap;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author cdr
 */
public abstract class SlicePanel extends JPanel implements TypeSafeDataProvider, Disposable {
  private final SliceTreeBuilder myBuilder;
  private final JTree myTree;

  private final AutoScrollToSourceHandler myAutoScrollToSourceHandler = new AutoScrollToSourceHandler() {
    protected boolean isAutoScrollMode() {
      return isAutoScroll();
    }

    protected void setAutoScrollMode(final boolean state) {
      setAutoScroll(state);
    }
  };
  private UsagePreviewPanel myUsagePreviewPanel;
  private final Project myProject;
  private boolean isDisposed;

  // rehash map on each PSI modification since SmartPsiPointer's hashCode() and equals() are changed
  private static class DuplicateMap extends THashMap<SliceUsage, List<SliceNode>> {
    private long timeStamp = -1;
    private DuplicateMap() {
      super(new TObjectHashingStrategy<SliceUsage>() {
        public int computeHashCode(SliceUsage object) {
          return object.getUsageInfo().hashCode();
        }

        public boolean equals(SliceUsage o1, SliceUsage o2) {
          return o1.getUsageInfo().equals(o2.getUsageInfo());
        }
      });
    }

    @Override
    public List<SliceNode> put(SliceUsage key, List<SliceNode> value) {
      long count = key.getElement().getManager().getModificationTracker().getModificationCount();
      if (count != timeStamp) {
        Map<SliceUsage, List<SliceNode>> map = new THashMap<SliceUsage, List<SliceNode>>(_hashingStrategy);
        for (Map.Entry<SliceUsage, List<SliceNode>> entry : map.entrySet()) {
          SliceUsage usage = entry.getKey();
          PsiElement element = usage.getElement();
          if (!element.isValid()) continue;
          List<SliceNode> list = entry.getValue();
          map.put(usage, list);
        }
        clear();
        putAll(map);
        timeStamp = count;
      }
      return super.put(key, value);
    }
  }

  public SlicePanel(Project project, final SliceUsage root) {
    super(new BorderLayout());
    myProject = project;
    myTree = createTree();

    Map<SliceUsage, List<SliceNode>> targetEqualUsages = new DuplicateMap();

    myBuilder = new SliceTreeBuilder(myTree, project);
    myBuilder.setCanYieldUpdate(true);
    Disposer.register(this, myBuilder);
    final SliceNode rootNode = new SliceRootNode(project, root, targetEqualUsages, myBuilder);
    myBuilder.setRoot(rootNode);

    layoutPanel();
    myBuilder.addSubtreeToUpdate((DefaultMutableTreeNode)myTree.getModel().getRoot(), new Runnable() {
      public void run() {
        myBuilder.expand(rootNode, new Runnable(){
          public void run() {
            myBuilder.select(rootNode.myCachedChildren.get(0)); //first there is ony one child
          }
        });
        treeSelectionChanged();
      }
    });
  }

  private void layoutPanel() {
    if (myUsagePreviewPanel != null) {
      Disposer.dispose(myUsagePreviewPanel);
    }
    removeAll();
    if (isPreview()) {
      Splitter splitter = new Splitter(false, UsageViewSettings.getInstance().PREVIEW_USAGES_SPLITTER_PROPORTIONS);
      splitter.setFirstComponent(ScrollPaneFactory.createScrollPane(myTree));
      myUsagePreviewPanel = new UsagePreviewPanel(myProject);
      Disposer.register(this, myUsagePreviewPanel);
      splitter.setSecondComponent(myUsagePreviewPanel);
      add(splitter, BorderLayout.CENTER);
    }
    else {
      add(ScrollPaneFactory.createScrollPane(myTree), BorderLayout.CENTER);
    }

    add(createToolbar().getComponent(), BorderLayout.WEST);

    revalidate();
  }

  public void dispose() {
    if (myUsagePreviewPanel != null) {
      UsageViewSettings.getInstance().PREVIEW_USAGES_SPLITTER_PROPORTIONS = ((Splitter)myUsagePreviewPanel.getParent()).getProportion();
      myUsagePreviewPanel = null;
    }
    
    isDisposed = true;
    ToolTipManager.sharedInstance().unregisterComponent(myTree);
  }

  private JTree createTree() {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode();
    final JTree tree = new Tree(new DefaultTreeModel(root)){
      protected void paintComponent(Graphics g) {
        DuplicateNodeRenderer.paintDuplicateNodesBackground(g, this);
        super.paintComponent(g);
      }
    };
    tree.setOpaque(false);

    tree.setToggleClickCount(-1);
    SliceUsageCellRenderer renderer = new SliceUsageCellRenderer();
    renderer.setOpaque(false);
    tree.setCellRenderer(renderer);
    UIUtil.setLineStyleAngled(tree);
    tree.setRootVisible(false);
    
    tree.setShowsRootHandles(true);
    tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
    tree.setSelectionPath(new TreePath(root.getPath()));
    //ActionGroup group = (ActionGroup)ActionManager.getInstance().getAction(IdeActions.GROUP_METHOD_HIERARCHY_POPUP);
    //PopupHandler.installPopupHandler(tree, group, ActionPlaces.METHOD_HIERARCHY_VIEW_POPUP, ActionManager.getInstance());
    EditSourceOnDoubleClickHandler.install(tree);

    new TreeSpeedSearch(tree);
    TreeUtil.installActions(tree);
    TreeToolTipHandler.install(tree);
    ToolTipManager.sharedInstance().registerComponent(tree);

    myAutoScrollToSourceHandler.install(tree);

    tree.getSelectionModel().addTreeSelectionListener(new TreeSelectionListener() {
      public void valueChanged(final TreeSelectionEvent e) {
        treeSelectionChanged();
      }
    });

    tree.addKeyListener(new KeyAdapter() {
      public void keyPressed(KeyEvent e) {
        if (KeyEvent.VK_ENTER == e.getKeyCode()) {
          List<Navigatable> navigatables = getNavigatables();
          if (navigatables.isEmpty()) return;
          for (Navigatable navigatable : navigatables) {
            if (navigatable instanceof AbstractTreeNode && ((AbstractTreeNode)navigatable).getValue() instanceof Usage) {
              navigatable = (Usage)((AbstractTreeNode)navigatable).getValue();
            }
            if (navigatable.canNavigateToSource()) {
              navigatable.navigate(false);
              if (navigatable instanceof Usage) {
                ((Usage)navigatable).highlightInEditor();
              }
            }
          }
          e.consume();
        }
      }
    });

    return tree;
  }

  private void treeSelectionChanged() {
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        if (isDisposed) return;
        List<UsageInfo> infos = getSelectedUsageInfos();
        if (infos != null && myUsagePreviewPanel != null) {
          myUsagePreviewPanel.updateLayout(infos);
        }
      }
    });
  }

  private List<UsageInfo> getSelectedUsageInfos() {
    TreePath[] paths = myTree.getSelectionPaths();
    if (paths == null) return null;
    final ArrayList<UsageInfo> result = new ArrayList<UsageInfo>();
    for (TreePath path : paths) {
      Object lastPathComponent = path.getLastPathComponent();
      if (lastPathComponent instanceof DefaultMutableTreeNode) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode)lastPathComponent;
        Object userObject = node.getUserObject();
        if (userObject instanceof SliceNode) {
          result.add(((SliceNode)userObject).getValue().getUsageInfo());
        }
      }
    }
    if (result.isEmpty()) return null;
    return result;
  }

  public void calcData(DataKey key, DataSink sink) {
    if (key == PlatformDataKeys.NAVIGATABLE_ARRAY) {
      List<Navigatable> navigatables = getNavigatables();
      if (!navigatables.isEmpty()) {
        sink.put(PlatformDataKeys.NAVIGATABLE_ARRAY, navigatables.toArray(new Navigatable[navigatables.size()]));
      }
    }
  }

  @NotNull
  private List<Navigatable> getNavigatables() {
    TreePath[] paths = myTree.getSelectionPaths();
    if (paths == null) return Collections.emptyList();
    final ArrayList<Navigatable> navigatables = new ArrayList<Navigatable>();
    for (TreePath path : paths) {
      Object lastPathComponent = path.getLastPathComponent();
      if (lastPathComponent instanceof DefaultMutableTreeNode) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode)lastPathComponent;
        Object userObject = node.getUserObject();
        if (userObject instanceof Navigatable) {
          navigatables.add((Navigatable)userObject);
        }
        else if (node instanceof Navigatable) {
          navigatables.add((Navigatable)node);
        }
      }
    }
    return navigatables;
  }

  private ActionToolbar createToolbar() {
    final DefaultActionGroup actionGroup = new DefaultActionGroup();
    actionGroup.add(new RefreshAction(myTree));
    actionGroup.add(myAutoScrollToSourceHandler.createToggleAction());
    actionGroup.add(new CloseAction());
    actionGroup.add(new ToggleAction(UsageViewBundle.message("preview.usages.action.text"), "preview", IconLoader.getIcon("/actions/preview.png")) {
      public boolean isSelected(AnActionEvent e) {
        return isPreview();
      }

      public void setSelected(AnActionEvent e, boolean state) {
        setPreview(state);
        layoutPanel();
      }
    });

    actionGroup.add(new AnalyzeLeavesAction(myBuilder));

    //actionGroup.add(new ContextHelpAction(HELP_ID));

    return ActionManager.getInstance().createActionToolbar(ActionPlaces.TYPE_HIERARCHY_VIEW_TOOLBAR, actionGroup, false);
  }

  public abstract boolean isAutoScroll();

  public abstract void setAutoScroll(boolean autoScroll);

  public abstract boolean isPreview();

  public abstract void setPreview(boolean preview);

  private class CloseAction extends CloseTabToolbarAction {
    public final void actionPerformed(final AnActionEvent e) {
      close();
    }
  }

  protected abstract void close();

  private final class RefreshAction extends com.intellij.ide.actions.RefreshAction {
    private RefreshAction(JComponent tree) {
      super(IdeBundle.message("action.refresh"), IdeBundle.message("action.refresh"), IconLoader.getIcon("/actions/sync.png"));
      registerShortcutOn(tree);
    }

    public final void actionPerformed(final AnActionEvent e) {
      myBuilder.addSubtreeToUpdate((DefaultMutableTreeNode)myTree.getModel().getRoot());
    }

    public final void update(final AnActionEvent event) {
      final Presentation presentation = event.getPresentation();
      presentation.setEnabled(true);
    }
  }
}
