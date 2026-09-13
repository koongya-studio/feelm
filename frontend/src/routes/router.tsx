/*
 * 라우트 정의.
 * 경로와 레이아웃 배정은 frontend/docs/screens/frontend-structure.md 3·4장을 따른다.
 * 레이아웃 라우트는 경로가 없는(pathless) 라우트이므로 하위 라우트에 절대 경로를 쓴다.
 *
 * 인증 화면(0.x)은 업무 -302에서 AuthLayout 아래에 추가했다. 인증 **상태**에 따른
 * 라우트 보호는 여전히 범위 밖이다(와이어프레임 이동표 `비로그인 ⇢ 0.6`). 보호 계층이
 * 필요해지면 이 파일에서 레이아웃 라우트를 감싸 처리한다.
 */
import { createBrowserRouter } from 'react-router';
import AppLayout from '../layouts/AppLayout';
import AuthLayout from '../layouts/AuthLayout';
import FullScreenLayout from '../layouts/FullScreenLayout';
import OnboardingLayout from '../layouts/OnboardingLayout';
import AccountFindPage from '../pages/auth/AccountFindPage';
import EmailSignupPage from '../pages/auth/EmailSignupPage';
import LoginPage from '../pages/auth/LoginPage';
import PasswordResetPage from '../pages/auth/PasswordResetPage';
import SignupCompletePage from '../pages/auth/SignupCompletePage';
import SignupMethodPage from '../pages/auth/SignupMethodPage';
import SocialSignupPage from '../pages/auth/SocialSignupPage';
import StartPage from '../pages/auth/StartPage';
import VerifyEmailPage from '../pages/auth/VerifyEmailPage';
import FilmPage from '../pages/film/FilmPage';
import FrameDetailPage from '../pages/film/FrameDetailPage';
import PopcornBucketPage from '../pages/film/PopcornBucketPage';
import HomePage from '../pages/home/HomePage';
import AccountManagePage from '../pages/my/AccountManagePage';
import BlockedUsersPage from '../pages/my/BlockedUsersPage';
import MyDashboardPage from '../pages/my/MyDashboardPage';
import MyRatingsPage from '../pages/my/MyRatingsPage';
import NotificationSettingsPage from '../pages/my/NotificationSettingsPage';
import PrivacySettingsPage from '../pages/my/PrivacySettingsPage';
import ProfileEditPage from '../pages/my/ProfileEditPage';
import ProfilePreviewPage from '../pages/my/ProfilePreviewPage';
import PublicProfilePage from '../pages/my/PublicProfilePage';
import RecommendationHistoryPage from '../pages/my/RecommendationHistoryPage';
import SettingsPage from '../pages/my/SettingsPage';
import WithdrawPage from '../pages/my/WithdrawPage';
import TasteComparePage from '../pages/my/TasteComparePage';
import NotificationListPage from '../pages/notifications/NotificationListPage';
import PushPreviewPage from '../pages/notifications/PushPreviewPage';
import OttAnalyzingPage from '../pages/otts/OttAnalyzingPage';
import OttFinderPage from '../pages/otts/OttFinderPage';
import OttInsufficientPage from '../pages/otts/OttInsufficientPage';
import OttManagePage from '../pages/otts/OttManagePage';
import OttResultDetailPage from '../pages/otts/OttResultDetailPage';
import OttResultPage from '../pages/otts/OttResultPage';
import MovieDetailPage from '../pages/movies/MovieDetailPage';
import MovieRatingPage from '../pages/movies/MovieRatingPage';
import RatingResultPage from '../pages/movies/RatingResultPage';
import OnboardingCompletePage from '../pages/onboarding/OnboardingCompletePage';
import OttOnboardingPage from '../pages/onboarding/OttOnboardingPage';
import TasteOnboardingPage from '../pages/onboarding/TasteOnboardingPage';
import PartyAnalyzingPage from '../pages/party/PartyAnalyzingPage';
import PartyInvitationPage from '../pages/party/PartyInvitationPage';
import PartyJoinedPage from '../pages/party/PartyJoinedPage';
import PartyListPage from '../pages/party/PartyListPage';
import PartyPopcornMixPage from '../pages/party/PartyPopcornMixPage';
import PartyRecommendationPage from '../pages/party/PartyRecommendationPage';
import PartySetupPage from '../pages/party/PartySetupPage';
import PartySizePage from '../pages/party/PartySizePage';
import PartyPopcornPage from '../pages/party/PartyPopcornPage';
import RecommendationsPage from '../pages/recommendations/RecommendationsPage';
import ReportDetailPage from '../pages/reports/ReportDetailPage';
import ReportIssuedPage from '../pages/reports/ReportIssuedPage';
import ReportListPage from '../pages/reports/ReportListPage';
import SearchPage from '../pages/search/SearchPage';
import NotFoundPage from '../pages/system/NotFoundPage';
import WatchConfirmPage from '../pages/watch/WatchConfirmPage';
import { ROUTE_PATTERN } from './paths';

export const router = createBrowserRouter(
  [
    {
      element: <AuthLayout />,
      children: [
        { path: ROUTE_PATTERN.authStart, element: <StartPage /> },
        { path: ROUTE_PATTERN.signupMethod, element: <SignupMethodPage /> },
        { path: ROUTE_PATTERN.signupSocial, element: <SocialSignupPage /> },
        { path: ROUTE_PATTERN.signupEmail, element: <EmailSignupPage /> },
        { path: ROUTE_PATTERN.signupComplete, element: <SignupCompletePage /> },
        { path: ROUTE_PATTERN.login, element: <LoginPage /> },
        { path: ROUTE_PATTERN.accountFind, element: <AccountFindPage /> },
        { path: ROUTE_PATTERN.passwordReset, element: <PasswordResetPage /> },
        { path: ROUTE_PATTERN.verifyEmail, element: <VerifyEmailPage /> },
      ],
    },
    {
      element: <OnboardingLayout />,
      children: [
        { path: ROUTE_PATTERN.onboardingTaste, element: <TasteOnboardingPage /> },
        { path: ROUTE_PATTERN.onboardingOtt, element: <OttOnboardingPage /> },
        { path: ROUTE_PATTERN.onboardingComplete, element: <OnboardingCompletePage /> },
      ],
    },
    {
      element: <AppLayout />,
      children: [
        { path: ROUTE_PATTERN.home, element: <HomePage /> },
        { path: ROUTE_PATTERN.recommendations, element: <RecommendationsPage /> },
        { path: ROUTE_PATTERN.film, element: <FilmPage /> },
        { path: ROUTE_PATTERN.popcornBucket, element: <PopcornBucketPage /> },
        { path: ROUTE_PATTERN.search, element: <SearchPage /> },
        { path: ROUTE_PATTERN.ottManage, element: <OttManagePage /> },

        // 7.1 MY 대시보드 (업무 -305). 이전에는 NotInScopePage였다
        { path: ROUTE_PATTERN.my, element: <MyDashboardPage /> },

        // 이번 단계 구현 범위가 아닌 탭. REF-01의 5탭 구성을 검증하기 위해 라우트는 둔다.
        { path: ROUTE_PATTERN.party, element: <PartyListPage /> },

        // MY 기록 목록 (업무 -306). 와이어프레임이 하단 내비를 그리는 탭 화면이다
        { path: ROUTE_PATTERN.recommendationHistory, element: <RecommendationHistoryPage /> },
        { path: ROUTE_PATTERN.myRatings, element: <MyRatingsPage tab="rated" /> },
        { path: ROUTE_PATTERN.unratedMovies, element: <MyRatingsPage tab="unrated" /> },

        // 7.3 취향 리포트 목록 (업무 -308). 와이어프레임이 하단 내비를 그렸다
        { path: ROUTE_PATTERN.tasteReports, element: <ReportListPage /> },

        // 7.8 설정 (업무 -307). 와이어프레임이 하단 내비를 그린 유일한 설정 화면이다
        { path: ROUTE_PATTERN.settings, element: <SettingsPage /> },
      ],
    },
    {
      element: <FullScreenLayout />,
      children: [
        { path: ROUTE_PATTERN.movieDetail, element: <MovieDetailPage /> },
        { path: ROUTE_PATTERN.movieRating, element: <MovieRatingPage /> },
        { path: ROUTE_PATTERN.ratingResult, element: <RatingResultPage /> },
        { path: ROUTE_PATTERN.watchConfirm, element: <WatchConfirmPage /> },
        { path: ROUTE_PATTERN.frameDetail, element: <FrameDetailPage /> },
        { path: ROUTE_PATTERN.partyPopcorn, element: <PartyPopcornPage /> },
        { path: ROUTE_PATTERN.partyPopcornMix, element: <PartyPopcornMixPage /> },
        { path: ROUTE_PATTERN.partyNew, element: <PartySizePage /> },
        { path: ROUTE_PATTERN.partySetup, element: <PartySetupPage /> },
        { path: ROUTE_PATTERN.partyAnalyzing, element: <PartyAnalyzingPage /> },
        { path: ROUTE_PATTERN.partyRecommendations, element: <PartyRecommendationPage /> },
        { path: ROUTE_PATTERN.partyInvitation, element: <PartyInvitationPage /> },
        { path: ROUTE_PATTERN.partyJoined, element: <PartyJoinedPage /> },

        // MY 프로필 (업무 -305). 7.1만 탭 화면이고 나머지는 전체화면이다
        { path: ROUTE_PATTERN.profileEdit, element: <ProfileEditPage /> },
        { path: ROUTE_PATTERN.profilePreview, element: <ProfilePreviewPage /> },
        { path: ROUTE_PATTERN.publicProfile, element: <PublicProfilePage /> },
        { path: ROUTE_PATTERN.tasteCompare, element: <TasteComparePage /> },

        // 설정 하위 화면 (업무 -307). 와이어프레임에 하단 내비가 없다
        { path: ROUTE_PATTERN.accountManage, element: <AccountManagePage /> },
        { path: ROUTE_PATTERN.withdraw, element: <WithdrawPage /> },
        { path: ROUTE_PATTERN.privacySettings, element: <PrivacySettingsPage /> },
        { path: ROUTE_PATTERN.notificationSettings, element: <NotificationSettingsPage /> },
        { path: ROUTE_PATTERN.blockedUsers, element: <BlockedUsersPage /> },

        // 취향 리포트 · 알림 · OTT 찾기 (업무 -308). 와이어프레임에 하단 내비가 없다
        { path: ROUTE_PATTERN.reportIssued, element: <ReportIssuedPage /> },
        { path: ROUTE_PATTERN.report, element: <ReportDetailPage from="film" /> },
        { path: ROUTE_PATTERN.myReport, element: <ReportDetailPage from="my" /> },
        { path: ROUTE_PATTERN.notifications, element: <NotificationListPage /> },
        { path: ROUTE_PATTERN.pushPreview, element: <PushPreviewPage /> },
        { path: ROUTE_PATTERN.ottFinder, element: <OttFinderPage /> },
        { path: ROUTE_PATTERN.ottAnalyzing, element: <OttAnalyzingPage /> },
        { path: ROUTE_PATTERN.ottResult, element: <OttResultPage /> },
        { path: ROUTE_PATTERN.ottInsufficient, element: <OttInsufficientPage /> },
        { path: ROUTE_PATTERN.ottResultDetail, element: <OttResultDetailPage /> },
        { path: ROUTE_PATTERN.notFound, element: <NotFoundPage /> },
      ],
    },
  ],
  { basename: '/app' },
);
