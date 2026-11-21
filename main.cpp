#include "mainwindow.h"
#include <QApplication>
#include <QStyleFactory>

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);

    // Set application style
    app.setStyle(QStyleFactory::create("Fusion"));

    // Set application info
    app.setApplicationName("Audio Streamer");
    app.setApplicationVersion("1.0.0");
    app.setOrganizationName("YourCompany");

    MainWindow mainWindow;
    mainWindow.show();

    return app.exec();
}