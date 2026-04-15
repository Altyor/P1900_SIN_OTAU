package com.siliconlabs.bledemo.features.firmware_browser.domain

object UiStrings {
    // Firmware Browser
    var firmwareBrowserTitle = "Sélection du firmware"
    var selectProduct = "Sélectionner le produit"
    var selectPartNumber = "Sélectionner le numéro de pièce"
    var selectCardToUpdate = "Sélectionner la carte à mettre à jour"
    var antenna = "Antenne"
    var power = "Puissance"
    var connecting = "Connexion au serveur de firmware..."
    var downloading = "Téléchargement du firmware..."
    var firmwareReady = "Firmware prêt"
    var noProductsFound = "Aucun produit trouvé sur le serveur de firmware."
    var connectionFailed = "Échec de connexion au serveur de firmware"
    var failedToListPns = "Échec de la liste des numéros de pièce"
    var failedToReadConfig = "Échec de lecture de la configuration"
    var failedToDownload = "Échec du téléchargement du firmware"
    var retry = "Réessayer"
    var back = "Retour"
    var changeProduct = "Changer de produit"

    // Model validation
    var modelMismatchTitle = "Modèle incompatible"
    var modelMismatchMessage = "Le modèle de l'appareil connecté \"%s\" ne correspond pas au produit sélectionné (attendu : %s).\n\nVeuillez déconnecter et vérifier que vous avez le bon appareil."
    var disconnect = "Déconnecter"

    // Toast messages
    var firmwareSelected = "Firmware sélectionné et prêt pour l'OTA"
    var noFirmwareSelected = "Aucun firmware sélectionné"
}
