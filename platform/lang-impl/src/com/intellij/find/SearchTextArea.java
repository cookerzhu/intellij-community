// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.find;

import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.find.editorHeaderActions.Utils;
import com.intellij.icons.AllIcons;
import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.ex.ActionButtonLook;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.actionSystem.impl.IdeaActionButtonLook;
import com.intellij.openapi.editor.EditorCopyPasteHelper;
import com.intellij.openapi.editor.ex.util.EditorUtil;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.StartupUiUtil;
import com.intellij.util.ui.UIUtil;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.border.CompoundBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.plaf.TextUI;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.PlainDocument;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.List;

import static java.awt.event.InputEvent.*;
import static javax.swing.ScrollPaneConstants.*;

public class SearchTextArea extends JPanel implements PropertyChangeListener/*, FocusListener*/ {
  public static final String JUST_CLEARED_KEY = "JUST_CLEARED";
  public static final KeyStroke NEW_LINE_KEYSTROKE
    = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, (SystemInfo.isMac ? META_DOWN_MASK : CTRL_DOWN_MASK) | SHIFT_DOWN_MASK);

  private static final ActionButtonLook FIELD_INPLACE_LOOK = new IdeaActionButtonLook() {
    @Override
    public void paintBorder(Graphics g, JComponent component, @ActionButtonComponent.ButtonState int state) {
      if (component.isFocusOwner() && component.isEnabled()) {
        Rectangle rect = new Rectangle(component.getSize());
        JBInsets.removeFrom(rect, component.getInsets());
        SYSTEM_LOOK.paintLookBorder(g, rect, JBUI.CurrentTheme.ActionButton.focusedBorder());
      }
      else {
        super.paintBorder(g, component, ActionButtonComponent.NORMAL);
      }
    }

    @Override
    public void paintBackground(Graphics g, JComponent component, int state) {
      if (((MyActionButton)component).isRolloverState()) {
        super.paintBackground(g, component, state);
      }
    }
  };

  private final JTextArea myTextArea;
  private final boolean mySearchMode;
  private final boolean myInfoMode;
  private final JLabel myInfoLabel;
  private final JPanel myIconsPanel = new NonOpaquePanel();
  private final ActionButton myNewLineButton;
  private final ActionButton myClearButton;
  private final NonOpaquePanel myExtraActionsPanel = new NonOpaquePanel();
  private final JBScrollPane myScrollPane;
  private final ActionButton myHistoryPopupButton;
  private boolean myMultilineEnabled = true;

  public SearchTextArea(boolean searchMode) {
    this(new JBTextArea(), searchMode, false);
  }

  public SearchTextArea(@NotNull JTextArea textArea, boolean searchMode, boolean infoMode) {
    this(textArea, searchMode, infoMode, false);
  }

  public SearchTextArea(@NotNull JTextArea textArea, boolean searchMode, boolean infoMode, boolean allowInsertTabInMultiline) {
    myTextArea = textArea;
    mySearchMode = searchMode;
    myInfoMode = infoMode;
    updateFont();

    myTextArea.addPropertyChangeListener("background", this);
    myTextArea.addPropertyChangeListener("font", this);
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        if (allowInsertTabInMultiline && myTextArea.getText().contains("\n")) {
          if (myTextArea.isEditable() && myTextArea.isEnabled()) {
            myTextArea.replaceSelection("\t");
          }
          else {
            UIManager.getLookAndFeel().provideErrorFeedback(myTextArea);
          }
        }
        else {
          myTextArea.transferFocus();
        }      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0)), myTextArea);
    new DumbAwareAction() {
      @Override
      public void actionPerformed(@NotNull AnActionEvent e) {
        myTextArea.transferFocusBackward();
      }
    }.registerCustomShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, SHIFT_DOWN_MASK)), myTextArea);
    KeymapUtil.reassignAction(myTextArea, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), NEW_LINE_KEYSTROKE, WHEN_FOCUSED);
    myTextArea.setDocument(new PlainDocument() {
      @Override
      public void insertString(int offs, String str, AttributeSet a) throws BadLocationException {
        if (getProperty("filterNewlines") == Boolean.TRUE && str.indexOf('\n') >= 0) {
          str = StringUtil.replace(str, "\n", " ");
        }
        if (!StringUtil.isEmpty(str)) super.insertString(offs, str, a);
      }
    });
    if (Registry.is("ide.find.field.trims.pasted.text", false)) {
      myTextArea.getDocument().putProperty(EditorCopyPasteHelper.TRIM_TEXT_ON_PASTE_KEY, Boolean.TRUE);
    }
    myTextArea.getDocument().addDocumentListener(new DocumentAdapter() {
      @Override
      protected void textChanged(@NotNull DocumentEvent e) {
        if (e.getType() == DocumentEvent.EventType.INSERT) {
          myTextArea.putClientProperty(JUST_CLEARED_KEY, null);
        }
        updateIconsLayout();
      }
    });
    myTextArea.setOpaque(false);
    myScrollPane = new JBScrollPane(myTextArea, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_AS_NEEDED) {
      @Override
      public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        TextUI ui = myTextArea.getUI();
        if (ui != null) {
          d.height = Math.min(d.height, ui.getPreferredSize(myTextArea).height);
        }
        return d;
      }

      @Override
      public void doLayout() {
        super.doLayout();
        JScrollBar hsb = getHorizontalScrollBar();
        if (StringUtil.getLineBreakCount(getTextArea().getText()) == 0 && hsb.isVisible()) {
          Rectangle hsbBounds = hsb.getBounds();
          hsb.setVisible(false);
          Rectangle bounds = getViewport().getBounds();
          bounds = bounds.union(hsbBounds);
          getViewport().setBounds(bounds);
        }
      }

      @Override
      protected void setupCorners() {
        super.setupCorners();
        setBorder(JBUI.Borders.empty(2, 0, 2, 2));
      }

      @Override
      public void updateUI() {
        super.updateUI();
        setBorder(JBUI.Borders.empty(2, 0, 2, 2));
      }
    };
    myTextArea.setBorder(new Border() {
      @Override
      public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {}

      @Override
      public Insets getBorderInsets(Component c) {
        if (SystemInfo.isMac) {
          return new JBInsets(3, 0, 2, 0);
        }
        else {
          int bottom = (StringUtil.getLineBreakCount(myTextArea.getText()) > 0) ? 2 : StartupUiUtil.isUnderDarcula() ? 1 : 0;
          int top = myTextArea.getFontMetrics(myTextArea.getFont()).getHeight() <= 16 ? 2 : 1;
          if (JBUIScale.isUsrHiDPI()) {
            bottom = 0;
            top = 2;
          }
          return new JBInsets(top, 0, bottom, 0);
        }
      }

      @Override
      public boolean isBorderOpaque() {
        return false;
      }
    });
    myScrollPane.getViewport().setBorder(null);
    myScrollPane.getViewport().setOpaque(false);
    myScrollPane.setOpaque(false);

    myInfoLabel = new JBLabel(UIUtil.ComponentStyle.SMALL);
    myInfoLabel.setForeground(JBColor.GRAY);

    myHistoryPopupButton = new MyActionButton(new ShowHistoryAction(), false);
    myClearButton = new MyActionButton(new ClearAction(), false);
    myNewLineButton = new MyActionButton(new NewLineAction(), false);

    updateLayout();
  }

  @Override
  public void updateUI() {
    super.updateUI();
    updateFont();
    setBackground(UIUtil.getTextFieldBackground());
  }

  private void updateFont() {
    if (myTextArea != null) {
      if (Registry.is("ide.find.use.editor.font", false)) {
        myTextArea.setFont(EditorUtil.getEditorFont());
      }
      else {
        myTextArea.setFont(UIManager.getFont("TextField.font"));
      }
    }
  }

  protected void updateLayout() {
    Insets i = SystemInfo.isLinux ? JBUI.insets(2) : JBUI.insets(1);
    setLayout(new MigLayout("flowx, ins " + i.top + " " + i.left + " " + i.bottom + " " + i.right + ", gapx " + JBUIScale.scale(3)));
    removeAll();
    add(myHistoryPopupButton, "ay baseline, gaptop " + JBUIScale.scale(1));
    add(myScrollPane, "ay top, growx, push, growy");
    //TODO combine icons/info modes
    if (myInfoMode) {
      add(myInfoLabel, "gapright " + JBUIScale.scale(4));
    }
    JPanel iconsPanelWrapper = new NonOpaquePanel(new BorderLayout());
    iconsPanelWrapper.setBorder(JBUI.Borders.emptyTop(1));
    JPanel p = new NonOpaquePanel(new BorderLayout());
    p.add(myIconsPanel, BorderLayout.NORTH);
    iconsPanelWrapper.add(p, BorderLayout.WEST);
    iconsPanelWrapper.add(myExtraActionsPanel, BorderLayout.CENTER);
    add(iconsPanelWrapper, "ay top, growy");
    updateIconsLayout();
  }

  protected boolean isNewLineAvailable() {
    return myMultilineEnabled;
  }

  private void updateIconsLayout() {
    if (myIconsPanel.getParent() == null) {
      return;
    }

    boolean showClearIcon = !StringUtil.isEmpty(myTextArea.getText());
    boolean showNewLine = isNewLineAvailable();
    boolean wrongVisibility =
      ((myClearButton.getParent() == null) == showClearIcon) || ((myNewLineButton.getParent() == null) == showNewLine);

    boolean multiline = StringUtil.getLineBreakCount(myTextArea.getText()) > 0;
    if (wrongVisibility) {
      myIconsPanel.removeAll();
      myIconsPanel.setLayout(new GridLayout(1, showClearIcon && showNewLine ? 2 : 1, 0, 0));
      if (showClearIcon) {
        myIconsPanel.add(myClearButton);
      }
      if (showNewLine) {
        myIconsPanel.add(myNewLineButton);
      }
      myIconsPanel.revalidate();
      myIconsPanel.repaint();
      myScrollPane.setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_AS_NEEDED);
      myScrollPane.setVerticalScrollBarPolicy(multiline ? VERTICAL_SCROLLBAR_AS_NEEDED : VERTICAL_SCROLLBAR_NEVER);
      myScrollPane.getHorizontalScrollBar().setVisible(multiline);
      myScrollPane.revalidate();
      doLayout();
    }
  }

  public List<Component> setExtraActions(AnAction... actions) {
    myExtraActionsPanel.removeAll();
    myExtraActionsPanel.setBorder(JBUI.Borders.empty());
    ArrayList<Component> addedButtons = new ArrayList<>();
    if (actions != null && actions.length > 0) {
      JPanel buttonsGrid = new NonOpaquePanel(new GridLayout(1, actions.length, 0, 0));
      for (AnAction action : actions) {
        ActionButton button = new MyActionButton(action, true);
        addedButtons.add(button);
        buttonsGrid.add(button);
      }
      myExtraActionsPanel.setLayout(new BorderLayout());
      myExtraActionsPanel.add(buttonsGrid, BorderLayout.NORTH);
      myExtraActionsPanel.setBorder(new CompoundBorder(JBUI.Borders.customLine(JBColor.border(), 0, 1, 0, 0), JBUI.Borders.emptyLeft(4)));
    }
    return addedButtons;
  }

  public void updateExtraActions() {
    for (ActionButton button : UIUtil.findComponentsOfType(myExtraActionsPanel, ActionButton.class)) {
      button.update();
    }
  }

  private final KeyAdapter myEnterRedispatcher = new KeyAdapter() {
    @Override
    public void keyPressed(KeyEvent e) {
      if (e.getKeyCode() == KeyEvent.VK_ENTER && SearchTextArea.this.getParent() != null) {
        SearchTextArea.this.getParent().dispatchEvent(e);
      }
    }
  };

  public void setMultilineEnabled(boolean enabled) {
    if (myMultilineEnabled == enabled) return;

    myMultilineEnabled = enabled;
    myTextArea.getDocument().putProperty("filterNewlines", myMultilineEnabled ? null : Boolean.TRUE);
    if (!myMultilineEnabled) {
      myTextArea.getInputMap().put(KeyStroke.getKeyStroke("shift UP"), "selection-begin-line");
      myTextArea.getInputMap().put(KeyStroke.getKeyStroke("shift DOWN"), "selection-end-line");
      myTextArea.addKeyListener(myEnterRedispatcher);
    }
    else {
      myTextArea.getInputMap().put(KeyStroke.getKeyStroke("shift UP"), "selection-up");
      myTextArea.getInputMap().put(KeyStroke.getKeyStroke("shift DOWN"), "selection-down");
      myTextArea.removeKeyListener(myEnterRedispatcher);
    }
    updateIconsLayout();
  }

  @NotNull
  public JTextArea getTextArea() {
    return myTextArea;
  }

  @Override
  public Dimension getMinimumSize() {
    return getPreferredSize();
  }

  @Override
  public void propertyChange(PropertyChangeEvent evt) {
    if ("background".equals(evt.getPropertyName())) {
      repaint();
    }
    if ("font".equals(evt.getPropertyName())) {
      updateLayout();
    }
  }

  public void setInfoText(String info) {
    myInfoLabel.setText(info);
  }

  private class ShowHistoryAction extends DumbAwareAction {

    ShowHistoryAction() {
      super(FindBundle.message(mySearchMode ? "find.search.history" : "find.replace.history"),
            FindBundle.message(mySearchMode ? "find.search.history" : "find.replace.history"),
            AllIcons.Actions.SearchWithHistory);
      registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts("ShowSearchHistory"), myTextArea);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      FeatureUsageTracker.getInstance().triggerFeatureUsed("find.recent.search");
      FindInProjectSettings findInProjectSettings = FindInProjectSettings.getInstance(e.getProject());
      String[] recent = mySearchMode ? findInProjectSettings.getRecentFindStrings()
                                     : findInProjectSettings.getRecentReplaceStrings();
      JBList<String> historyList = new JBList<>(ArrayUtil.reverseArray(recent));
      Utils.showCompletionPopup(SearchTextArea.this, historyList, null, myTextArea, null);
    }
  }

  private class ClearAction extends DumbAwareAction {
    ClearAction() {
      super(AllIcons.Actions.Close);
      getTemplatePresentation().setHoveredIcon(AllIcons.Actions.CloseHovered);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      myTextArea.putClientProperty(JUST_CLEARED_KEY, !myTextArea.getText().isEmpty());
      myTextArea.setText("");
    }
  }

  private class NewLineAction extends DumbAwareAction {
    NewLineAction() {
      super(FindBundle.message("find.new.line"), null, AllIcons.Actions.SearchNewLine);
      getTemplatePresentation().setHoveredIcon(AllIcons.Actions.SearchNewLineHover);
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {
      new DefaultEditorKit.InsertBreakAction().actionPerformed(new ActionEvent(myTextArea, 0, "action"));
    }
  }

  private static class MyActionButton extends ActionButton {

    private MyActionButton(@NotNull AnAction action, boolean focusable) {
      super(action, action.getTemplatePresentation().clone(), ActionPlaces.UNKNOWN, ActionToolbar.DEFAULT_MINIMUM_BUTTON_SIZE);

      setLook(focusable ? FIELD_INPLACE_LOOK : ActionButtonLook.INPLACE_LOOK);
      setFocusable(focusable);
      updateIcon();
    }

    @Override
    protected DataContext getDataContext() {
      return DataManager.getInstance().getDataContext(this);
    }

    @Override
    public int getPopState() {
      return isSelected() ? SELECTED : super.getPopState();
    }

    boolean isRolloverState() {
      return super.isRollover();
    }

    @Override
    public Icon getIcon() {
      if (isEnabled() && isSelected()) {
        Icon selectedIcon = myPresentation.getSelectedIcon();
        if (selectedIcon != null) return selectedIcon;
      }
      return super.getIcon();
    }
  }
}
