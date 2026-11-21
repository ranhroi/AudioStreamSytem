#ifndef MAINWINDOW_H
#define MAINWINDOW_H

#include <QMainWindow>
#include <QSettings>
#include <QTimer>
#include <QTime>
#include <QSystemTrayIcon>
#include <QMenu>
#include <QCloseEvent>
#include <QMutex>
#include <QMutexLocker>
#include "audiocapturer.h"

QT_BEGIN_NAMESPACE
namespace Ui { class MainWindow; }
QT_END_NAMESPACE

class MainWindow : public QMainWindow
{
    Q_OBJECT

public:
    MainWindow(QWidget *parent = nullptr);
    ~MainWindow();

protected:
    void closeEvent(QCloseEvent *event) override;

private slots:
    // Button handlers
    void on_startButton_clicked();
    void on_stopButton_clicked();

    // System tray handlers
    void on_trayIconActivated(QSystemTrayIcon::ActivationReason reason);
    void on_trayStartStop();
    void on_trayShowHide();

    // AudioCapturer signal handlers
    void on_statusChanged(const QString &status);
    void on_errorOccurred(const QString &error);
    void on_bytesSent(quint64 bytes);
    void on_currentRmsChanged(double rms);
    void on_streamingStarted();
    void on_streamingStopped();

private:
    void setupUI();
    void setupConnections();
    void setupSystemTray();
    void loadSettings();
    void saveSettings();
    void updateUIState(bool streaming);
    void updateTrayState(bool streaming);
    void appendLog(const QString &message, const QString &type = "INFO");
    void updateStats();
    void toggleStreaming();
    void showWindow();
    void performCleanShutdown();

    AudioConfig getCurrentConfig();
    QString getLocalIPAddress();

    Ui::MainWindow *ui;
    AudioCapturer *audioCapturer;
    QSettings *settings;
    QTimer *ipRefreshTimer;

    // System tray
    QSystemTrayIcon *trayIcon;
    QMenu *trayMenu;
    QAction *trayStartStopAction;
    QAction *trayShowHideAction;
    QAction *trayQuitAction;

    // Mutex chống khởi động chồng lặp
    QMutex startMutex;
    bool isStarting;

    // Stats tracking
    quint64 lastBytesSent;
    double currentBandwidth;
    double lastRmsValue;

    // Window state
    bool minimizeToTray;
};

#endif // MAINWINDOW_H