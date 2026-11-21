#include "audiocapturer.h"
#include <QDebug>
#include <QtMath>
#include <algorithm>

AudioCapturer::AudioCapturer(QObject *parent)
    : QObject(parent)
    , udpSocket(nullptr)
    , workerThread(nullptr)
    , threadRunning(false)
    , deviceEnumerator(nullptr)
    , audioDevice(nullptr)
    , audioClient(nullptr)
    , captureClient(nullptr)
    , waveFormat(nullptr)
    , totalBytesSent(0)
    , packetCount(0)
    , currentRms(0.0)
{
    statsTimer = new QTimer(this);
    connect(statsTimer, &QTimer::timeout, this, [this]() {
        if (threadRunning.load()) {
            emit bytesSent(totalBytesSent);
            emit currentRmsChanged(currentRms);
        }
    });
}

AudioCapturer::~AudioCapturer()
{
    stopStreaming();
}

bool AudioCapturer::startStreaming(const QString &androidIp, int port, const AudioConfig &config)
{
    if (threadRunning.load()) return false;

    audioConfig = config;
    bytesPerSample = audioConfig.bitsPerSample / 8;
    bytesPerFrame = bytesPerSample * audioConfig.channels;       // Kích thước frame sau convert
    stereoBytesPerFrame = bytesPerSample * 2;                    // Luôn capture stereo (2 kênh)

    // Điều chỉnh chunkSize để là bội số của bytesPerFrame (tránh cắt dở mẫu)
    int frameSize = bytesPerFrame;
    int adjustedChunk = (audioConfig.chunkSize / frameSize) * frameSize;
    if (adjustedChunk == 0) adjustedChunk = frameSize;
    audioConfig.chunkSize = adjustedChunk;

    androidAddress = QHostAddress(androidIp);
    androidPort = port;

    if (androidAddress.isNull()) {
        emit errorOccurred("Địa chỉ IP Android không hợp lệ.");
        return false;
    }

    threadRunning.store(true);

    // Tạo luồng xử lý
    workerThread = QThread::create([this]() {
        this->runCaptureLoop();
    });
    connect(workerThread, &QThread::finished, workerThread, &QObject::deleteLater);
    workerThread->start();

    statsTimer->start(1000);
    emit streamingStarted();
    return true;
}

void AudioCapturer::stopStreaming()
{
    if (!threadRunning.load()) return;

    threadRunning.store(false);
    if (workerThread) {
        workerThread->quit();
        workerThread->wait(3000);
    }
    statsTimer->stop();
    emit streamingStopped();
}

bool AudioCapturer::isStreaming() const
{
    return threadRunning.load();
}

// HÀM CHẠY CHÍNH TRÊN LUỒNG PHỤ
void AudioCapturer::runCaptureLoop()
{
    // 1. Khởi tạo COM cho luồng này
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr)) {
        emit errorOccurred("Không thể khởi tạo COM trong luồng capture.");
        threadRunning.store(false);
        return;
    }

    // 2. Khởi tạo WASAPI (luôn stereo)
    if (!initializeWASAPI()) {
        CoUninitialize();
        threadRunning.store(false);
        return;
    }

    // 3. Tạo UDP socket ngay trong luồng này
    udpSocket = new QUdpSocket();

    hr = audioClient->Start();
    if (FAILED(hr)) {
        emit errorOccurred("Không thể bắt đầu capture WASAPI.");
        delete udpSocket;
        cleanupWASAPI();
        CoUninitialize();
        threadRunning.store(false);
        return;
    }

    emit statusChanged("Đang capture âm thanh hệ thống (stereo) và gửi đến Android...");

    UINT32 packetLength = 0;
    BYTE *pData = nullptr;
    UINT32 numFramesAvailable;
    DWORD flags;
    QByteArray localBuffer;
    localBuffer.reserve(MAX_BUFFER_SIZE);

    while (threadRunning.load()) {
        Sleep(1); // Giảm tải CPU

        hr = captureClient->GetNextPacketSize(&packetLength);
        if (FAILED(hr)) {
            emit errorOccurred("Lỗi GetNextPacketSize: " + QString::number(hr));
            break;
        }

        while (packetLength != 0 && threadRunning.load()) {
            hr = captureClient->GetBuffer(&pData, &numFramesAvailable, &flags, nullptr, nullptr);
            if (FAILED(hr)) {
                emit errorOccurred("Lỗi GetBuffer: " + QString::number(hr));
                break;
            }

            if (!(flags & AUDCLNT_BUFFERFLAGS_SILENT)) {
                int stereoDataSize = numFramesAvailable * stereoBytesPerFrame; // stereo bytes
                QByteArray stereoChunk(reinterpret_cast<const char*>(pData), stereoDataSize);

                // Chuyển sang mono nếu cấu hình yêu cầu
                QByteArray audioData = (audioConfig.channels == 1) ? convertToMono(stereoChunk) : stereoChunk;

                localBuffer.append(audioData);

                // Gửi các gói UDP có kích thước chunkSize (đã được căn chỉnh)
                while (localBuffer.size() >= audioConfig.chunkSize) {
                    QByteArray chunk = localBuffer.left(audioConfig.chunkSize);
                    localBuffer.remove(0, audioConfig.chunkSize);

                    currentRms = calculateRMS(chunk);

                    if (udpSocket) {
                        qint64 sent = udpSocket->writeDatagram(chunk, androidAddress, androidPort);
                        if (sent > 0) {
                            totalBytesSent += sent;
                            packetCount++;
                        }
                    }
                }
            }

            captureClient->ReleaseBuffer(numFramesAvailable);
            hr = captureClient->GetNextPacketSize(&packetLength);
        }
    }

    // Dọn dẹp
    if (audioClient) audioClient->Stop();
    delete udpSocket;
    udpSocket = nullptr;
    cleanupWASAPI();
    CoUninitialize();
    emit statusChanged("Đã dừng luồng capture.");
}


bool AudioCapturer::initializeWASAPI()
{
    HRESULT hr = CoCreateInstance(__uuidof(MMDeviceEnumerator), NULL, CLSCTX_ALL,
                                  __uuidof(IMMDeviceEnumerator), (void**)&deviceEnumerator);
    if (FAILED(hr)) {
        emit errorOccurred("Không thể tạo MMDeviceEnumerator.");
        return false;
    }

    hr = deviceEnumerator->GetDefaultAudioEndpoint(eRender, eConsole, &audioDevice);
    if (FAILED(hr)) {
        emit errorOccurred("Không lấy được thiết bị âm thanh mặc định.");
        return false;
    }

    hr = audioDevice->Activate(__uuidof(IAudioClient), CLSCTX_ALL, NULL, (void**)&audioClient);
    if (FAILED(hr)) {
        emit errorOccurred("Không thể activate IAudioClient.");
        return false;
    }

    // Lấy mix format thực tế của thiết bị
    WAVEFORMATEX* pMixFormat = nullptr;
    hr = audioClient->GetMixFormat(&pMixFormat);
    if (FAILED(hr)) {
        emit errorOccurred("Không thể lấy mix format từ audio client.");
        return false;
    }

    int actualSampleRate = pMixFormat->nSamplesPerSec;
    int actualBits = pMixFormat->wBitsPerSample;
    int actualChannels = pMixFormat->nChannels;

    // Tạo format người dùng mong muốn (stereo)
    WAVEFORMATEX desiredFormat;
    desiredFormat.wFormatTag = WAVE_FORMAT_PCM;
    desiredFormat.nChannels = 2;
    desiredFormat.nSamplesPerSec = audioConfig.sampleRate;
    desiredFormat.wBitsPerSample = audioConfig.bitsPerSample;
    desiredFormat.nBlockAlign = (desiredFormat.nChannels * desiredFormat.wBitsPerSample) / 8;
    desiredFormat.nAvgBytesPerSec = desiredFormat.nSamplesPerSec * desiredFormat.nBlockAlign;
    desiredFormat.cbSize = 0;

    // Thử khởi tạo với format mong muốn
    hr = audioClient->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
                                 10000000, 0, &desiredFormat, NULL);

    if (FAILED(hr)) {
        // Format người dùng không được hỗ trợ -> fallback sang mix format thực tế
        QString msg = QString("Không hỗ trợ format %1Hz %2bit stereo (lỗi 0x%3). Dùng format thiết bị: %4Hz %5bit %6 kênh.")
                          .arg(audioConfig.sampleRate)
                          .arg(audioConfig.bitsPerSample)
                          .arg(hr, 0, 16)
                          .arg(actualSampleRate)
                          .arg(actualBits)
                          .arg(actualChannels);
        emit errorOccurred(msg);  // Báo lỗi nhưng không làm treo

        // Giải phóng audio client cũ, tạo lại và khởi tạo với mix format
        audioClient->Release();
        hr = audioDevice->Activate(__uuidof(IAudioClient), CLSCTX_ALL, NULL, (void**)&audioClient);
        if (FAILED(hr)) {
            CoTaskMemFree(pMixFormat);
            emit errorOccurred("Không thể tạo lại audio client.");
            return false;
        }

        hr = audioClient->Initialize(AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_LOOPBACK,
                                     10000000, 0, pMixFormat, NULL);
        if (FAILED(hr)) {
            CoTaskMemFree(pMixFormat);
            emit errorOccurred("WASAPI không thể khởi tạo với mix format.");
            return false;
        }

        // Cập nhật cấu hình theo format thực tế
        audioConfig.sampleRate = actualSampleRate;
        audioConfig.bitsPerSample = actualBits;
        audioConfig.channels = actualChannels;   // Có thể là 1, 2, 6, 8...
        bytesPerSample = actualBits / 8;
        stereoBytesPerFrame = bytesPerSample * actualChannels;
        emit statusChanged(QString("Đã chuyển sang format thiết bị: %1 Hz, %2 bit, %3 kênh")
                               .arg(actualSampleRate).arg(actualBits).arg(actualChannels));
    } else {
        // Format người dùng được hỗ trợ
        audioConfig.sampleRate = desiredFormat.nSamplesPerSec;
        audioConfig.bitsPerSample = desiredFormat.wBitsPerSample;
        audioConfig.channels = 2;
        bytesPerSample = desiredFormat.wBitsPerSample / 8;
        stereoBytesPerFrame = bytesPerSample * 2;
        emit statusChanged(QString("Dùng format yêu cầu: %1 Hz, %2 bit, stereo")
                               .arg(audioConfig.sampleRate).arg(audioConfig.bitsPerSample));
    }

    CoTaskMemFree(pMixFormat);

    hr = audioClient->GetService(__uuidof(IAudioCaptureClient), (void**)&captureClient);
    if (FAILED(hr)) {
        emit errorOccurred("Không thể lấy IAudioCaptureClient.");
        return false;
    }

    return true;
}

void AudioCapturer::cleanupWASAPI()
{
    if (captureClient) { captureClient->Release(); captureClient = nullptr; }
    if (audioClient) { audioClient->Release(); audioClient = nullptr; }
    if (audioDevice) { audioDevice->Release(); audioDevice = nullptr; }
    if (deviceEnumerator) { deviceEnumerator->Release(); deviceEnumerator = nullptr; }
    if (waveFormat) { delete waveFormat; waveFormat = nullptr; }
}

double AudioCapturer::calculateRMS(const QByteArray &data)
{
    if (data.isEmpty()) return 0.0;
    const qint16* samples = reinterpret_cast<const qint16*>(data.constData());
    int sampleCount = data.size() / sizeof(qint16);
    double sum = 0.0;
    for (int i = 0; i < sampleCount; ++i) {
        double sample = samples[i] / 32768.0;
        sum += sample * sample;
    }
    return qSqrt(sum / sampleCount) * 100.0;
}

QByteArray AudioCapturer::convertToMono(const QByteArray &stereoData)
{
    // stereoData: 16-bit PCM, mỗi sample 2 byte, xen kẽ L,R,L,R,...
    int numStereoFrames = stereoData.size() / stereoBytesPerFrame; // mỗi frame 4 byte
    const qint16 *stereo = reinterpret_cast<const qint16*>(stereoData.constData());
    QByteArray monoData;
    monoData.reserve(numStereoFrames * sizeof(qint16));

    for (int i = 0; i < numStereoFrames; ++i) {
        qint32 left = stereo[i * 2];
        qint32 right = stereo[i * 2 + 1];
        qint32 average = (left + right) / 2;
        qint16 sample = qBound(-32768, average, 32767);
        monoData.append(reinterpret_cast<const char*>(&sample), sizeof(qint16));
    }
    return monoData;
}