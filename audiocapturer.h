#ifndef AUDIOCAPTURER_H
#define AUDIOCAPTURER_H

#include <QObject>
#include <QUdpSocket>
#include <QHostAddress>
#include <QTimer>
#include <QThread>
#include <QByteArray>
#include <atomic>
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>

// Cấu hình âm thanh
struct AudioConfig {
    int sampleRate = 48000;
    int channels = 2;          // Số kênh MONG MUỐN (1 hoặc 2)
    int bitsPerSample = 16;
    int chunkSize = 1024;      // Kích thước mỗi gói UDP (byte)
};

class AudioCapturer : public QObject
{
    Q_OBJECT

public:
    explicit AudioCapturer(QObject *parent = nullptr);
    ~AudioCapturer();

    bool startStreaming(const QString &androidIp, int port,
                        const AudioConfig &config = AudioConfig());
    void stopStreaming();
    bool isStreaming() const;

signals:
    void statusChanged(const QString &status);
    void errorOccurred(const QString &error);
    void bytesSent(quint64 bytes);
    void streamingStarted();
    void streamingStopped();
    void currentRmsChanged(double rms);

private:
    void runCaptureLoop();          // Chạy trong luồng riêng
    bool initializeWASAPI();        // Luôn khởi tạo với stereo
    void cleanupWASAPI();
    double calculateRMS(const QByteArray &data);
    QByteArray convertToMono(const QByteArray &stereoData);   // Chuyển stereo 16-bit -> mono 16-bit

    // Socket và mạng
    QUdpSocket *udpSocket;
    QHostAddress androidAddress;
    int androidPort;

    // Cấu hình
    AudioConfig audioConfig;        // Cấu hình mong muốn (có thể mono)
    int bytesPerSample;             // = bitsPerSample/8
    int bytesPerFrame;              // = bytesPerSample * audioConfig.channels (sau khi convert)
    int stereoBytesPerFrame;        // = bytesPerSample * 2 (khi capture từ WASAPI)

    // Quản lý luồng
    QThread *workerThread;
    std::atomic<bool> threadRunning;

    // WASAPI objects
    IMMDeviceEnumerator *deviceEnumerator;
    IMMDevice *audioDevice;
    IAudioClient *audioClient;
    IAudioCaptureClient *captureClient;
    WAVEFORMATEX *waveFormat;

    // Thống kê
    QTimer *statsTimer;
    quint64 totalBytesSent;
    quint64 packetCount;
    double currentRms;

    static const int MAX_BUFFER_SIZE = 65536;
};

#endif // AUDIOCAPTURER_H