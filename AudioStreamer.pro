QT += core gui multimedia network widgets

CONFIG += c++17

# You can make your code fail to compile if it uses deprecated APIs.
# In order to do so, uncomment the following line.
#DEFINES += QT_DISABLE_DEPRECATED_BEFORE=0x060000    # disables all the APIs deprecated before Qt 6.0.0

SOURCES += \
    audiocapturer.cpp \
    main.cpp \
    mainwindow.cpp

HEADERS += \
    audiocapturer.h \
    mainwindow.h

FORMS += \
    mainwindow.ui

RESOURCES += resources.qrc

RC_ICONS = apps_media_streaming.ico

LIBS += -lwinmm -lole32 -luuid



# Xác định thư mục đích (DESTDIR) tùy theo chế độ build
CONFIG(debug, debug|release) {
    DESTDIR = $$OUT_PWD/debug
} else {
    DESTDIR = $$OUT_PWD/release

    # Đường dẫn file exe sau khi build
    DEPLOY_TARGET = $$shell_quote($$DESTDIR/$$TARGET".exe")

    # Lệnh windeployqt
    # Lưu ý: Bỏ --no-compiler-runtime nếu bạn muốn nó tự copy các file DLL của MinGW
    QMAKE_POST_LINK += $$shell_quote($$[QT_INSTALL_BINS]/windeployqt.exe) \
                       --no-translations \
                       $$DEPLOY_TARGET
}


# Default rules for deployment.
qnx: target.path = /tmp/$${TARGET}/bin
else: unix:!android: target.path = /opt/$${TARGET}/bin
!isEmpty(target.path): INSTALLS += target
