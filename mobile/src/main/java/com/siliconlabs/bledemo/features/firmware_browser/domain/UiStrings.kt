package com.siliconlabs.bledemo.features.firmware_browser.domain

object UiStrings {
    // Firmware Browser
    var firmwareBrowserTitle = "Sélection du firmware"
    var selectProduct = "Sélectionner le produit"
    var selectPartNumber = "Sélectionner le numéro de pièce"
    var selectCardToUpdate = "Sélectionner la carte à mettre à jour"
    var antenna = "Antenne"
    var power = "Puissance"
    var both = "Antenne + Puissance"
    var connecting = "Connexion au serveur de firmware..."
    var downloading = "Téléchargement du firmware..."
    var firmwareReady = "Firmware prêt"
    var noProductsFound = "Aucun produit trouvé sur le serveur de firmware."
    var connectionFailed = "Échec de connexion au serveur de firmware"
    var failedToListPns = "Échec de la liste des numéros de pièce"
    var failedToReadConfig = "Échec de lecture de la configuration"
    var failedToDownload = "Échec du téléchargement du firmware"
    var noCredentials = "Identifiants SFTP non configurés.\n\nVeuillez placer le fichier Secret_OTAU.ini dans le stockage de la tablette et relancer l'application."
    var retry = "Réessayer"
    var back = "Retour"
    var changeProduct = "Changer de produit"

    // Model validation
    var modelMismatchTitle = "Modèle incompatible"
    var modelMismatchMessage = "Le modèle de l'appareil connecté \"%s\" ne correspond pas au produit sélectionné (attendu : %s).\n\nVeuillez déconnecter et vérifier que vous avez le bon appareil."
    var disconnect = "Déconnecter"

    // Operator override
    var overridePromptTitle = "Informations de l'appareil illisibles"
    var overridePromptMessage = "Impossible de lire les informations de l'appareil après plusieurs tentatives. Continuer avec le mode opérateur ?"
    var overrideYes = "Oui"
    var overrideCodeTitle = "Saisir le code opérateur"
    var overrideCodeHint = "Code"
    var overrideConfirm = "Confirmer"
    var overrideCancel = "Annuler"
    var overrideIncorrectCode = "Code incorrect. Veuillez réessayer."

    // Device status
    var statusPreOta = "Connecté — Pré-OTA"
    var statusPreOtaBoth = "Connecté — Pré-OTA (Antenne + Puissance)"
    var statusUploading = "Mise à jour en cours..."
    var statusRebooting = "Redémarrage de l'appareil..."
    var statusReconnecting = "Reconnexion en cours..."
    var statusPostOta = "Connecté — Post-OTA"
    var statusSecondOta = "Antenne terminée.\nLancement de la mise à jour Puissance..."
    var statusPowerTransfer = "Transfert série vers carte Puissance...\nVeuillez patienter (~1m30s)"
    var statusAlreadyUpToDate = "Firmware déjà à jour"
    var versionsAlreadyMatch = "Déjà à jour"
    var statusReconnectFailed = "Échec de reconnexion.\nVeuillez déconnecter et réessayer."
    var statusOtaRetrying = "Connexion perdue pendant la mise à jour.\nReconnexion et reprise... (tentative %d/%d)"
    var statusOtaRetryFailed = "Échec après %d tentatives.\nVeuillez déconnecter et réessayer."

    // Toast messages
    var firmwareSelected = "Firmware sélectionné et prêt pour l'OTA"
    var noFirmwareSelected = "Aucun firmware sélectionné"
}
