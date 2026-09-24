/*
 * 라우트 정의.
 * 경로와 레이아웃 배정은 frontend/docs/screens/frontend-structure.md 3·4장을 따른다.
 * 레이아웃 라우트는 경로가 없는(pathless) 라우트이므로 하위 라우트에 절대 경로를 쓴다.
 *
 * 인증 화면(0.x)은 업무 -302에서 AuthLayout 아래에 추가했다. **로그인이 필요한 라우트는
 * 전부 `RequireAuth` 아래에 있다**(업무 -610, 이동표 `비로그인 ⇢ 0.6`). 비로그인이면 가려던
 * 주소를 들고 0.6으로 가고, 세션을 되살리는 동안은 아무것도 그리지 않는다.
 *
 * 문지기 밖에 있는 것은 셋이다 — 0.x 인증 화면, 앱 진입점 `/`(로그인 전이면 0.1로 보낸다,
 * `HomeEntry`), 없는 경로. 온보딩(1.x)은 회원 API(API-023 · 024 · 027)를 부르므로 안쪽이다.
 */
import { createBrowserRouter } from 'react-router';
import AppLayout from '../layouts/AppLayout';
import AuthLayout from '../layouts/AuthLayout';
import FullScreenLayout from '../layouts/FullScreenLayout';
import OnboardingLayout from '../layouts/OnboardingLayout';
import AccountFindPage from '../pages/auth/AccountFindPage';
import EmailSignupPage from '../pages/auth/EmailSignupPage';
import LoginPage from '../pages/auth/LoginPage';
import OAuthCallbackPage from '../pages/auth/OAuthCallbackPage';
import PasswordResetPage from '../pages/auth/PasswordResetPage';
import SignupCompletePage from '../pages/auth/SignupCompletePage';
import SignupMethodPage from '../pages/auth/SignupMethodPage';
import SocialSignupProfilePage from '../pages/auth/SocialSignupProfilePage';
import StartPage from '../pages/auth/StartPage';
import FilmPage from '../pages/film/FilmPage';
import FrameDetailPage from '../pages/film/FrameDetailPage';
import PopcornBucketPage from '../pages/film/PopcornBucketPage';
import AccountManagePage from '../pages/my/AccountManagePage';
import BlockedUsersPage from '../pages/my/BlockedUsersPage';
import MyDashboardPage from '../pages/my/MyDashboardPage';
import NotificationSettingsPage from '../pages/my/NotificationSettingsPage';
import PasswordChangePage from '../pages/my/PasswordChangePage';
import PrivacySettingsPage from '../pages/my/PrivacySettingsPage';
import ProfileEditPage from '../pages/my/ProfileEditPage';
import PublicProfilePage from '../pages/my/PublicProfilePage';
import SettingsPage from '../pages/my/SettingsPage';
import WithdrawPage from '../pages/my/WithdrawPage';
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
import PartyInvitationPage from '../pages/party/PartyInvitationPage';
import PartyJoinedPage from '../pages/party/PartyJoinedPage';
import PartyHistoryPage from '../pages/party/PartyHistoryPage';
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
import HomeEntry from './HomeEntry';
import RequireAuth from './RequireAuth';
import { ROUTE_PATTERN } from './paths';

export const router = createBrowserRouter(
  [
    {
      element: <AuthLayout />,
      children: [
        { path: ROUTE_PATTERN.authStart, element: <StartPage /> },
        { path: ROUTE_PATTERN.signupMethod, element: <SignupMethodPage /> },
        { path: ROUTE_PATTERN.signupSocialProfile, element: <SocialSignupProfilePage /> },
        { path: ROUTE_PATTERN.signupEmail, element: <EmailSignupPage /> },
        { path: ROUTE_PATTERN.signupComplete, element: <SignupCompletePage /> },
        { path: ROUTE_PATTERN.login, element: <LoginPage /> },
        { path: ROUTE_PATTERN.accountFind, element: <AccountFindPage /> },
        { path: ROUTE_PATTERN.passwordReset, element: <PasswordResetPage /> },
        { path: ROUTE_PATTERN.oauthCallback, element: <OAuthCallbackPage /> },
      ],
    },
    {
      // 앱 진입점. 로그인 전이면 0.1, 로그인했으면 3.1 필름으로 보낸다(HomeEntry.tsx)
      element: <AppLayout />,
      children: [{ path: ROUTE_PATTERN.entry, element: <HomeEntry /> }],
    },
    {
      element: <FullScreenLayout />,
      children: [{ path: ROUTE_PATTERN.notFound, element: <NotFoundPage /> }],
    },
    {
      element: <RequireAuth />,
      children: [
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
            { path: ROUTE_PATTERN.recommendations, element: <RecommendationsPage /> },
            { path: ROUTE_PATTERN.film, element: <FilmPage /> },
            { path: ROUTE_PATTERN.popcornBucket, element: <PopcornBucketPage /> },
            { path: ROUTE_PATTERN.search, element: <SearchPage /> },
            { path: ROUTE_PATTERN.ottManage, element: <OttManagePage /> },

            // 7.1 MY 대시보드 (업무 -305). 이전에는 NotInScopePage였다
            { path: ROUTE_PATTERN.my, element: <MyDashboardPage /> },

            { path: ROUTE_PATTERN.party, element: <PartyListPage /> },

            /*
             * 파티팝콘 흐름과 파티원 팝콘 (업무 -462). **사용자 시안이 하단 내비를 그렸다.**
             * 5.9 · 5.5는 스스로 넘어가는 흐름이라 내비가 있으면 중간에 빠져나갈 수
             * 있고, 시안은 그것을 막지 않았다 — 파티 모드는 탭 안에서 일어나는 일이다.
             * 시안이 없는 5.2 · 5.3 · 5.6 · 5.7은 전체화면 그대로다.
             */
            { path: ROUTE_PATTERN.partyPopcorn, element: <PartyPopcornPage /> },
            { path: ROUTE_PATTERN.partyPopcornMix, element: <PartyPopcornMixPage /> },
            { path: ROUTE_PATTERN.partyRecommendations, element: <PartyRecommendationPage /> },

            /*
             * 5.10 파티 추천 이력 (업무 -826). 5.1의 `지난 파티`에서 들어오고 하단 내비가
             * 파티에 남는다 — 5.1로 돌아갈 길이 뒤로 가기 말고도 있어야 한다.
             */
            {
              path: ROUTE_PATTERN.partyRecommendationHistory,
              element: <PartyHistoryPage />,
            },

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
            { path: ROUTE_PATTERN.partyNew, element: <PartySizePage /> },
            { path: ROUTE_PATTERN.partySetup, element: <PartySetupPage /> },
            { path: ROUTE_PATTERN.partyInvitation, element: <PartyInvitationPage /> },
            { path: ROUTE_PATTERN.partyJoined, element: <PartyJoinedPage /> },

            // MY 프로필 (업무 -305). 7.1만 탭 화면이고 나머지는 전체화면이다
            { path: ROUTE_PATTERN.profileEdit, element: <ProfileEditPage /> },
            { path: ROUTE_PATTERN.profilePreview, element: <PublicProfilePage mode="preview" /> },
            { path: ROUTE_PATTERN.publicProfile, element: <PublicProfilePage /> },

            // 설정 하위 화면 (업무 -307). 와이어프레임에 하단 내비가 없다
            { path: ROUTE_PATTERN.accountManage, element: <AccountManagePage /> },
            // 7.8-A-2 비밀번호 변경 (업무 -391). 와이어프레임에 없이 새로 만든 화면이다
            { path: ROUTE_PATTERN.passwordChange, element: <PasswordChangePage /> },
            { path: ROUTE_PATTERN.withdraw, element: <WithdrawPage /> },
            { path: ROUTE_PATTERN.privacySettings, element: <PrivacySettingsPage /> },
            { path: ROUTE_PATTERN.notificationSettings, element: <NotificationSettingsPage /> },
            { path: ROUTE_PATTERN.blockedUsers, element: <BlockedUsersPage /> },

            // 취향 리포트 · 알림 · OTT 찾기 (업무 -308). 와이어프레임에 하단 내비가 없다
            { path: ROUTE_PATTERN.reportIssued, element: <ReportIssuedPage /> },
            { path: ROUTE_PATTERN.report, element: <ReportDetailPage /> },
            { path: ROUTE_PATTERN.myReport, element: <ReportDetailPage /> },
            { path: ROUTE_PATTERN.notifications, element: <NotificationListPage /> },
            { path: ROUTE_PATTERN.pushPreview, element: <PushPreviewPage /> },
            { path: ROUTE_PATTERN.ottFinder, element: <OttFinderPage /> },
            { path: ROUTE_PATTERN.ottAnalyzing, element: <OttAnalyzingPage /> },
            { path: ROUTE_PATTERN.ottResult, element: <OttResultPage /> },
            { path: ROUTE_PATTERN.ottInsufficient, element: <OttInsufficientPage /> },
            { path: ROUTE_PATTERN.ottResultDetail, element: <OttResultDetailPage /> },
          ],
        },
      ],
    },
  ],
  { basename: '/app' },
);
