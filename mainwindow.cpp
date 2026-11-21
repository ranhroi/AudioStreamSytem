#include "mainwindow.h"
#include "ui_mainwindow.h"
#include <QMessageBox>
#include <QRegularExpression>
#include <QRegularExpressionValidator>
#include <QNetworkInterface>
#include <QStyleFactory>
#include <QPalette>
#include <QTime>
#include <QTextCursor>
#include <QApplication>
#include <QStyle>

MainWindow::MainWindow(QWidget *parent)
    : QMainWindow(parent)
    , ui(new Ui::MainWindow)
    , audioCapturer(new AudioCapturer(this))
    , settings(new QSettings("AudioStreamer", "UDPClient", this))
    , ipRefreshTimer(new QTimer(this))
    , trayIcon(nullptr)
    , trayMenu(nullptr)
    , isStarting(false)
    , lastBytesSent(0)
    , currentBandwidth(0.0)
    , lastRmsValue(0.0)
    , minimizeToTray(true)
{
    ui->setupUi(this);
    setupUI();
    setupSystemTray();
    setupConnections();
    loadSettings();
    updateUIState(false);
    updateTrayState(false);

    // Hiển thị IP local
    QString localIP = getLocalIPAddress();
    appendLog(QString("💻 Local IP: %1").arg(localIP), "INFO");
    appendLog("📱 Ready to stream audio to Android device", "INFO");
    appendLog("ℹ️ Application will minimize to system tray when closed", "INFO");
}

MainWindow::~MainWindow()
{
    if (audioCapturer && audioCapturer->isStreaming()) {
        audioCapturer->stopStreaming();
    }
    saveSettings();
    delete ui;
}


void MainWindow::closeEvent(QCloseEvent *event)
{
    hide();
    if (trayIcon) {
        trayIcon->showMessage("Audio Streamer", "Ứng dụng đang chạy ngầm trong khay hệ thống.", QSystemTrayIcon::Information, 2000);
    }
    event->ignore();
}

// Hàm dọn dẹp sạch sẽ trước khi thoát
void MainWindow::performCleanShutdown()
{
    appendLog("🔄 Performing clean shutdown...", "WARNING");

    // 1. Dừng streaming nếu đang chạy
    if (audioCapturer) {
        if (audioCapturer->isStreaming()) {
            appendLog("⏹ Stopping audio stream...", "INFO");
            audioCapturer->stopStreaming();
        }
    }

    // 2. Dừng timer
    if (ipRefreshTimer && ipRefreshTimer->isActive()) {
        ipRefreshTimer->stop();
    }

    // 3. Ẩn tray icon
    if (trayIcon) {
        trayIcon->hide();
    }

    // 4. Lưu settings
    saveSettings();
    appendLog("💾 Settings saved.", "INFO");

    // 5. Log
    appendLog("👋 Audio Streamer is shutting down. Goodbye!", "INFO");

    // 6. Đợi một chút để log hiển thị (optional)
    QApplication::processEvents();
    QThread::msleep(100);
}

void MainWindow::setupUI()
{
    // Set window properties
    setWindowTitle("Audio Streamer - Send Audio to Android");
    setMinimumSize(550, 650);
    resize(600, 700);

    // Apply modern dark theme
    setStyleSheet(R"(
        QMainWindow {
            background-color: #1e1e2e;
        }
        QGroupBox {
            font-weight: bold;
            border: 2px solid #45475a;
            border-radius: 8px;
            margin-top: 12px;
            padding-top: 20px;
        }
        QGroupBox::title {
            subcontrol-origin: margin;
            left: 10px;
            padding: 0 8px 0 8px;
        }
        QPushButton {
            border-radius: 6px;
            padding: 8px 16px;
            font-weight: bold;
        }
        QPushButton:hover {
            opacity: 0.9;
        }
        QComboBox {
            padding: 6px 10px;
            border-radius: 4px;
            border: 1px solid #45475a;
            background-color: #2a2a3e;
            color: white;
        }
        QComboBox::drop-down {
            border: none;
        }
        QComboBox QAbstractItemView {
            background-color: #2a2a3e;
            color: white;
            selection-background-color: #4a4a6e;
        }
        QSpinBox {
            padding: 6px 10px;
            border-radius: 4px;
            border: 1px solid #45475a;
            background-color: #2a2a3e;
            color: white;
        }
        QLineEdit {
            padding: 6px 10px;
            border-radius: 4px;
            border: 1px solid #45475a;
            background-color: #2a2a3e;
            color: white;
        }
        QTextEdit {
            border-radius: 4px;
            border: 1px solid #45475a;
        }
        QLabel {
            color: #cccccc;
        }
        QLabel#statsLabel {
            font-family: 'Courier New', monospace;
            font-size: 12px;
            color: #00ff00;
            background-color: #0a0a1e;
            padding: 10px;
            border-radius: 4px;
        }
    )");

    // Setup IP input với validator
    QRegularExpression ipRegex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}"
                               "(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$");
    ui->serverIpLineEdit->setValidator(new QRegularExpressionValidator(ipRegex, this));
    ui->serverIpLineEdit->setPlaceholderText("192.168.1.22");

    // Setup port range
    ui->portSpinBox->setRange(1, 65535);
    ui->portSpinBox->setValue(2027);

    // Setup sample rate combo
    ui->sampleRateComboBox->addItem("48000 Hz (DVD Quality)", 48000);
    ui->sampleRateComboBox->addItem("44100 Hz (CD Quality)", 44100);
    ui->sampleRateComboBox->addItem("32000 Hz", 32000);
    ui->sampleRateComboBox->addItem("16000 Hz", 16000);
    ui->sampleRateComboBox->addItem("8000 Hz (Voice)", 8000);
    ui->sampleRateComboBox->setCurrentIndex(0);

    // Setup channel combo
    ui->channelComboBox->addItem("Stereo (2 Channels)", 2);
    ui->channelComboBox->addItem("Mono (1 Channel)", 1);
    ui->channelComboBox->setCurrentIndex(0);

    // Setup chunk size
    ui->bufferSizeSpinBox->setRange(256, 4096);
    ui->bufferSizeSpinBox->setSingleStep(256);
    ui->bufferSizeSpinBox->setValue(1024);
    ui->bufferSizeSpinBox->setSuffix(" bytes");

    // Style buttons
    ui->startButton->setStyleSheet(
        "QPushButton { background-color: #4CAF50; color: white; font-size: 14px; "
        "padding: 12px; }"
        "QPushButton:hover { background-color: #45a049; }"
        "QPushButton:disabled { background-color: #555; color: #888; }"
        );

    ui->stopButton->setStyleSheet(
        "QPushButton { background-color: #f44336; color: white; font-size: 14px; "
        "padding: 12px; }"
        "QPushButton:hover { background-color: #da190b; }"
        "QPushButton:disabled { background-color: #555; color: #888; }"
        );

    // Setup stats label
    ui->statsLabel->setObjectName("statsLabel");
    ui->statsLabel->setText("📊 Ready to stream...\n\n"
                            "Configure Android IP and press Start\n"
                            "Or use system tray icon for quick control");

    // Make log text read-only
    ui->statusTextEdit->setReadOnly(true);
    ui->statusTextEdit->setStyleSheet(
        "QTextEdit { background-color: #0a0a14; color: #00ff00; "
        "font-family: 'Consolas', 'Courier New', monospace; font-size: 12px; }"
        );

    QAction *quitAction = new QAction(this);
    quitAction->setShortcut(QKeySequence("Ctrl+Q"));
    connect(quitAction, &QAction::triggered, this, [this]() {
        close(); // Gọi closeEvent
    });
    this->addAction(quitAction);
}

void MainWindow::setupSystemTray()
{
    // Tạo system tray icon
    trayIcon = new QSystemTrayIcon(this);

    // Sử dụng icon mặc định của ứng dụng hoặc built-in icon
    //QIcon icon = qApp->style()->standardIcon(QStyle::SP_MediaPlay);
    trayIcon->setIcon(QIcon(":/images/media_streaming.png"));
    trayIcon->setToolTip("Audio Streamer - Not streaming");

    // Tạo context menu cho tray
    trayMenu = new QMenu(this);

    trayStartStopAction = new QAction("▶ Start Streaming", this);
    connect(trayStartStopAction, &QAction::triggered, this, &MainWindow::on_trayStartStop);
    trayMenu->addAction(trayStartStopAction);

    trayMenu->addSeparator();

    trayShowHideAction = new QAction("📂 Show Window", this);
    connect(trayShowHideAction, &QAction::triggered, this, &MainWindow::on_trayShowHide);
    trayMenu->addAction(trayShowHideAction);

    trayMenu->addSeparator();

    // Thêm action minimize to tray toggle
    QAction *minimizeAction = new QAction("🔽 Minimize to Tray on Close", this);
    minimizeAction->setCheckable(true);
    minimizeAction->setChecked(true);
    connect(minimizeAction, &QAction::toggled, this, [this](bool checked) {
        minimizeToTray = checked;
        appendLog(QString("Minimize to tray: %1").arg(checked ? "ON" : "OFF"), "INFO");
    });
    trayMenu->addAction(minimizeAction);

    trayMenu->addSeparator();


    trayQuitAction = new QAction("❌ Quit", this);
    connect(trayQuitAction, &QAction::triggered, this, [this]() {
        // Hỏi xác nhận trước khi thoát
        QMessageBox::StandardButton reply;

        if (audioCapturer && audioCapturer->isStreaming()) {
            reply = QMessageBox::question(
                this,
                "Xác nhận thoát",
                "⚠️ Đang streaming âm thanh đến Android!\n\n"
                "Bạn có chắc chắn muốn thoát không?\n"
                "Streaming sẽ bị dừng lại.",
                QMessageBox::Yes | QMessageBox::No,
                QMessageBox::No
                );
        } else {
            reply = QMessageBox::question(
                this,
                "Xác nhận thoát",
                "Bạn có muốn thoát Audio Streamer không?",
                QMessageBox::Yes | QMessageBox::No,
                QMessageBox::No
                );
        }

        if (reply == QMessageBox::Yes) {
            minimizeToTray = false; // Tắt minimize để quit thật sự

            // Dọn dẹp sạch sẽ
            if (audioCapturer && audioCapturer->isStreaming()) {
                audioCapturer->stopStreaming();
            }

            performCleanShutdown();
            QApplication::quit();
        }
    });
    trayMenu->addAction(trayQuitAction);

    trayIcon->setContextMenu(trayMenu);

    // Kết nối sự kiện click vào tray icon
    connect(trayIcon, &QSystemTrayIcon::activated,
            this, &MainWindow::on_trayIconActivated);

    trayIcon->show();
}

void MainWindow::setupConnections()
{
    // Button connections
    connect(ui->startButton, &QPushButton::clicked,
            this, &MainWindow::on_startButton_clicked);
    connect(ui->stopButton, &QPushButton::clicked,
            this, &MainWindow::on_stopButton_clicked);

    // AudioCapturer connections
    connect(audioCapturer, &AudioCapturer::streamingStarted,
            this, &MainWindow::on_streamingStarted);
    connect(audioCapturer, &AudioCapturer::streamingStopped,
            this, &MainWindow::on_streamingStopped);
    connect(audioCapturer, &AudioCapturer::statusChanged,
            this, &MainWindow::on_statusChanged);
    connect(audioCapturer, &AudioCapturer::errorOccurred,
            this, &MainWindow::on_errorOccurred);
    connect(audioCapturer, &AudioCapturer::bytesSent,
            this, &MainWindow::on_bytesSent);
    connect(audioCapturer, &AudioCapturer::currentRmsChanged,
            this, &MainWindow::on_currentRmsChanged);

    // IP refresh timer
    connect(ipRefreshTimer, &QTimer::timeout, this, [this]() {
        QString ip = getLocalIPAddress();
        ui->localIpLabel->setText(QString("💻 Local: %1").arg(ip));
    });
    ipRefreshTimer->start(5000);
}

// ==================== SYSTEM TRAY HANDLERS ====================

void MainWindow::on_trayIconActivated(QSystemTrayIcon::ActivationReason reason)
{
    switch (reason) {
    case QSystemTrayIcon::DoubleClick:
        // Double click: show/hide window
        on_trayShowHide();
        break;
    case QSystemTrayIcon::MiddleClick:
        // Middle click: start/stop streaming
        on_trayStartStop();
        break;
    default:
        break;
    }
}

void MainWindow::on_trayStartStop()
{
    toggleStreaming();
}

void MainWindow::on_trayShowHide()
{
    if (isVisible()) {
        hide();
        trayShowHideAction->setText("📂 Show Window");
    } else {
        showWindow();
    }
}

void MainWindow::showWindow()
{
    show();
    raise();
    activateWindow();
    trayShowHideAction->setText("📂 Hide Window");
}

// ==================== STREAMING CONTROL ====================

void MainWindow::toggleStreaming()
{
    // CHỐNG KHỞI ĐỘNG CHỒNG LẶP
    QMutexLocker locker(&startMutex);

    if (isStarting) {
        appendLog("⚠️ Already processing start/stop request, please wait...", "WARNING");
        return;
    }

    if (audioCapturer->isStreaming()) {
        // Đang streaming -> dừng
        appendLog("⏹ Stopping stream from tray...", "WARNING");
        audioCapturer->stopStreaming();
    } else {
        // Chưa streaming -> bắt đầu
        isStarting = true;

        QString androidIp = ui->serverIpLineEdit->text();
        int port = ui->portSpinBox->value();

        if (androidIp.isEmpty() || androidIp == "0.0.0.0" || androidIp == "127.0.0.1") {
            appendLog("❌ Cannot start: Invalid Android IP address", "ERROR");
            trayIcon->showMessage(
                "Audio Streamer",
                "Please configure Android IP address first!",
                QSystemTrayIcon::Warning,
                3000
                );
            isStarting = false;
            return;
        }

        AudioConfig config = getCurrentConfig();

        appendLog("🎬 Starting stream from tray...", "INFO");
        audioCapturer->startStreaming(androidIp, port, config);

        if (!audioCapturer->isStreaming()) {
            appendLog("❌ Failed to start streaming", "ERROR");
            isStarting = false;
        }
        // isStarting sẽ được reset trong on_streamingStarted hoặc on_streamingStopped
    }
}

void MainWindow::on_startButton_clicked()
{
    // CHỐNG KHỞI ĐỘNG CHỒNG LẶP
    QMutexLocker locker(&startMutex);

    if (isStarting) {
        appendLog("⚠️ Already processing, please wait...", "WARNING");
        return;
    }

    if (audioCapturer->isStreaming()) {
        appendLog("⚠️ Already streaming!", "WARNING");
        return;
    }

    QString androidIp = ui->serverIpLineEdit->text();
    int port = ui->portSpinBox->value();

    // Validate input
    if (androidIp.isEmpty()) {
        QMessageBox::warning(this, "Missing Information",
                             "📱 Please enter the Android device IP address.\n\n"
                             "Find this IP in your Android app header.\n"
                             "Example: 192.168.1.22");
        return;
    }

    if (androidIp == "0.0.0.0" || androidIp == "127.0.0.1") {
        QMessageBox::warning(this, "Invalid IP",
                             "⚠️ Please enter the actual WiFi IP of your Android device.");
        return;
    }

    isStarting = true;

    AudioConfig config = getCurrentConfig();
    saveSettings();

    appendLog("═══════════════════════════════════", "INFO");
    appendLog("🎬 Starting audio stream to Android...", "INFO");
    appendLog(QString("📱 Target: %1:%2").arg(androidIp).arg(port), "STREAM");
    appendLog(QString("🎵 %1 Hz, %2, Chunk: %3 bytes")
                  .arg(config.sampleRate)
                  .arg(config.channels == 2 ? "Stereo" : "Mono")
                  .arg(config.chunkSize), "STREAM");
    appendLog("═══════════════════════════════════", "INFO");

    audioCapturer->startStreaming(androidIp, port, config);
    if (!audioCapturer->isStreaming()) {
        appendLog("❌ Failed to start streaming!", "ERROR");
        isStarting = false;
    }
}

void MainWindow::on_stopButton_clicked()
{
    if (!audioCapturer->isStreaming()) {
        appendLog("⚠️ Not currently streaming!", "WARNING");
        return;
    }

    appendLog("⏹ Stopping audio stream...", "WARNING");
    audioCapturer->stopStreaming();
}

// ==================== AUDIOCAPTURER SIGNAL HANDLERS ====================

void MainWindow::on_streamingStarted()
{
    isStarting = false; // RESET FLAG CHỐNG CHỒNG LẶP

    updateUIState(true);
    updateTrayState(true);

    lastBytesSent = 0;
    currentBandwidth = 0.0;
    lastRmsValue = 0.0;

    appendLog("✅ Audio streaming started successfully!", "SUCCESS");
    appendLog(QString("📡 Sending audio to Android at %1:%2")
                  .arg(ui->serverIpLineEdit->text())
                  .arg(ui->portSpinBox->value()), "STREAM");

    // Hiển thị thông báo qua tray
    trayIcon->showMessage(
        "Audio Streamer",
        QString("Streaming started!\nSending to %1:%2")
            .arg(ui->serverIpLineEdit->text())
            .arg(ui->portSpinBox->value()),
        QSystemTrayIcon::Information,
        3000
        );

    updateStats();
}

void MainWindow::on_streamingStopped()
{
    isStarting = false; // RESET FLAG

    updateUIState(false);
    updateTrayState(false);

    double totalMB = lastBytesSent / (1024.0 * 1024.0);
    appendLog("✅ Audio streaming stopped.", "SUCCESS");
    appendLog(QString("📊 Total data sent: %1 MB").arg(totalMB, 0, 'f', 2), "INFO");

    trayIcon->showMessage(
        "Audio Streamer",
        QString("Streaming stopped.\nTotal sent: %1 MB").arg(totalMB, 0, 'f', 2),
        QSystemTrayIcon::Information,
        3000
        );

    updateStats();
}

void MainWindow::on_statusChanged(const QString &status)
{
    appendLog(status, "INFO");
}

void MainWindow::on_errorOccurred(const QString &error)
{
    isStarting = false; // RESET FLAG khi có lỗi

    appendLog("❌ " + error, "ERROR");

    // Hiển thị thông báo lỗi qua tray
    trayIcon->showMessage(
        "Audio Streamer - Error",
        error,
        QSystemTrayIcon::Critical,
        5000
        );

    // Chỉ hiển thị dialog cho lỗi nghiêm trọng
    if (error.contains("WASAPI") ||
        error.contains("audio client") ||
        error.contains("capture thread") ||
        error.contains("Invalid Android IP")) {
        QMessageBox::critical(this, "Streaming Error", error);
    }
}

void MainWindow::on_bytesSent(quint64 bytes)
{
    currentBandwidth = (bytes - lastBytesSent) / 1024.0;
    lastBytesSent = bytes;
    updateStats();
}

void MainWindow::on_currentRmsChanged(double rms)
{
    lastRmsValue = rms;
}

// ==================== UI HELPERS ====================

void MainWindow::updateUIState(bool streaming)
{
    ui->startButton->setEnabled(!streaming);
    ui->stopButton->setEnabled(streaming);
    ui->serverIpLineEdit->setEnabled(!streaming);
    ui->portSpinBox->setEnabled(!streaming);
    ui->sampleRateComboBox->setEnabled(!streaming);
    ui->channelComboBox->setEnabled(!streaming);
    ui->bufferSizeSpinBox->setEnabled(!streaming);

    if (streaming) {
        ui->startButton->setText("⏳ Streaming...");
        ui->statusTextEdit->setStyleSheet(
            "QTextEdit { background-color: #0a1a0a; color: #00ff00; "
            "font-family: 'Consolas', 'Courier New', monospace; font-size: 12px; }"
            );
    } else {
        ui->startButton->setText("▶ Start Streaming");
        ui->statusTextEdit->setStyleSheet(
            "QTextEdit { background-color: #0a0a14; color: #00ff00; "
            "font-family: 'Consolas', 'Courier New', monospace; font-size: 12px; }"
            );
    }
}

void MainWindow::updateTrayState(bool streaming)
{
    if (streaming) {
        trayIcon->setIcon(QIcon(":/images/media_streaming.png"));
        trayIcon->setToolTip("Audio Streamer - Streaming to Android...");
        trayStartStopAction->setText("⏹ Stop Streaming");
        trayIcon->setIcon(QIcon(":/images/stop_streaming.png"));
    } else {
        trayIcon->setIcon(QIcon(":/images/stop_streaming.png"));
        trayIcon->setToolTip("Audio Streamer - Not streaming");
        trayStartStopAction->setText("▶ Start Streaming");
        trayIcon->setIcon(QIcon(":/images/media_streaming.png"));
    }
}

void MainWindow::loadSettings()
{
    ui->serverIpLineEdit->setText(
        settings->value("android/ip", "192.168.1.22").toString());
    ui->portSpinBox->setValue(
        settings->value("android/port", 2027).toInt());
    ui->sampleRateComboBox->setCurrentIndex(
        settings->value("audio/sampleRateIndex", 0).toInt());
    ui->channelComboBox->setCurrentIndex(
        settings->value("audio/channelIndex", 0).toInt());
    ui->bufferSizeSpinBox->setValue(
        settings->value("audio/chunkSize", 1024).toInt());
    minimizeToTray = settings->value("ui/minimizeToTray", true).toBool();
}

void MainWindow::saveSettings()
{
    settings->setValue("android/ip", ui->serverIpLineEdit->text());
    settings->setValue("android/port", ui->portSpinBox->value());
    settings->setValue("audio/sampleRateIndex", ui->sampleRateComboBox->currentIndex());
    settings->setValue("audio/channelIndex", ui->channelComboBox->currentIndex());
    settings->setValue("audio/chunkSize", ui->bufferSizeSpinBox->value());
    settings->setValue("ui/minimizeToTray", minimizeToTray);
}

AudioConfig MainWindow::getCurrentConfig()
{
    AudioConfig config;
    config.sampleRate = ui->sampleRateComboBox->currentData().toInt();
    config.channels = ui->channelComboBox->currentData().toInt();
    config.chunkSize = ui->bufferSizeSpinBox->value();
    config.bitsPerSample = 16;
    return config;
}

QString MainWindow::getLocalIPAddress()
{
    const QList<QHostAddress> &addresses = QNetworkInterface::allAddresses();
    for (const QHostAddress &address : addresses) {
        if (address != QHostAddress::LocalHost &&
            address.protocol() == QAbstractSocket::IPv4Protocol) {
            return address.toString();
        }
    }
    return "127.0.0.1";
}

void MainWindow::appendLog(const QString &message, const QString &type)
{
    QString timestamp = QTime::currentTime().toString("hh:mm:ss.zzz");
    QString color;

    if (type == "ERROR") {
        color = "#ff4444";
    } else if (type == "WARNING") {
        color = "#ffaa00";
    } else if (type == "SUCCESS") {
        color = "#00ff00";
    } else if (type == "STREAM") {
        color = "#00ccff";
    } else {
        color = "#cccccc";
    }

    QString formattedMessage = QString("<span style='color: #888888;'>[%1]</span> "
                                       "<span style='color: %2;'>[%3]</span> "
                                       "<span style='color: #ffffff;'>%4</span>")
                                   .arg(timestamp, color, type, message);

    ui->statusTextEdit->append(formattedMessage);

    QTextCursor cursor = ui->statusTextEdit->textCursor();
    cursor.movePosition(QTextCursor::End);
    ui->statusTextEdit->setTextCursor(cursor);
}

void MainWindow::updateStats()
{
    double totalMB = lastBytesSent / (1024.0 * 1024.0);

    QString stats = QString(
                        "📊 <b>Streaming Statistics</b>\n"
                        "━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                        "📦 Total Sent: <b>%1 MB</b>\n"
                        "⚡ Bandwidth: <b>%2 KB/s</b>\n"
                        "━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                        "💻 Local IP: <b>%3</b>\n"
                        "📱 Android: <b>%4:%5</b>\n"
                        "━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                        "🎵 Sample Rate: <b>%6 Hz</b>\n"
                        "🔊 Channels: <b>%7</b>\n"
                        "📏 Chunk Size: <b>%8 bytes</b>\n"
                        "━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                        "📈 RMS Level: <b>%9</b>\n"
                        "🔽 Minimize to Tray: <b>%10</b>")
                        .arg(totalMB, 0, 'f', 2)
                        .arg(currentBandwidth, 0, 'f', 1)
                        .arg(getLocalIPAddress())
                        .arg(ui->serverIpLineEdit->text())
                        .arg(ui->portSpinBox->value())
                        .arg(ui->sampleRateComboBox->currentData().toInt())
                        .arg(ui->channelComboBox->currentData().toInt() == 2 ? "Stereo" : "Mono")
                        .arg(ui->bufferSizeSpinBox->value())
                        .arg(lastRmsValue, 0, 'f', 1)
                        .arg(minimizeToTray ? "ON" : "OFF");

    ui->statsLabel->setText(stats);
}